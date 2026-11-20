package me.rerere.rikkahub.data.github

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: GitHubApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GitHubApiClient(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            baseUrl = server.url("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun currentUserSendsGitHubHeadersAndParsesIdentity() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":42,"login":"octocat","name":"The Octocat","avatar_url":"https://example.test/avatar.png"}"""
            )
        )

        val user = client.currentUser("secret-token")

        assertEquals(42L, user.id)
        assertEquals("octocat", user.login)
        assertEquals("The Octocat", user.name)
        val request = server.takeRequest()
        assertEquals("/user", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
    }

    @Test
    fun primaryEmailPrefersPrimaryVerifiedAddress() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {"email":"secondary@example.test","primary":false,"verified":true},
                  {"email":"primary-unverified@example.test","primary":true,"verified":false},
                  {"email":"primary@example.test","primary":true,"verified":true}
                ]
                """.trimIndent()
            )
        )

        assertEquals("primary@example.test", client.primaryEmail("secret-token"))
        assertEquals("/user/emails", server.takeRequest().path)
    }

    @Test
    fun repositoryListParsesRepositoriesAndNextPage() = runTest {
        server.enqueue(
            MockResponse()
                .addHeader(
                    "Link",
                    "<${server.url("/user/repos?page=2")}>; rel=\"next\", " +
                        "<${server.url("/user/repos?page=4")}>; rel=\"last\"",
                )
                .setBody(
                    """
                    [
                      {
                        "id": 7,
                        "name": "rikkahub",
                        "full_name": "octocat/rikkahub",
                        "owner": {"login": "octocat"},
                        "private": true,
                        "html_url": "https://github.com/octocat/rikkahub",
                        "clone_url": "https://github.com/octocat/rikkahub.git",
                        "default_branch": "main",
                        "description": "AI IDE",
                        "language": "Kotlin"
                      }
                    ]
                    """.trimIndent()
                )
        )

        val page = client.listRepositories("secret-token")

        assertEquals(2, page.nextPage)
        assertEquals(1, page.repositories.size)
        val repository = page.repositories.single()
        assertEquals("octocat/rikkahub", repository.fullName)
        assertTrue(repository.isPrivate)
        assertEquals("Kotlin", repository.language)
        val request = server.takeRequest()
        assertEquals(
            "owner,collaborator,organization_member",
            request.requestUrl?.queryParameter("affiliation"),
        )
    }

    @Test
    fun missingNextLinkMeansNoMorePages() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        val page = client.listRepositories("secret-token")
        assertNull(page.nextPage)
        assertTrue(page.repositories.isEmpty())
    }

    @Test
    fun apiErrorsExposeGitHubMessageButNotToken() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"message":"Bad credentials","documentation_url":"https://docs.github.com"}""")
        )

        val error = assertThrows(GitHubApiException::class.java) {
            runTest { client.currentUser("secret-token") }
        }

        assertEquals(401, error.statusCode)
        assertTrue(error.message.orEmpty().contains("Bad credentials"))
        assertFalse(error.message.orEmpty().contains("secret-token"))
    }
}
