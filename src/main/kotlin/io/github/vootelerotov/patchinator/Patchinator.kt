package io.github.vootelerotov.patchinator

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.output.TermUi.editText
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.difflib.unifieddiff.UnifiedDiffFile
import com.github.difflib.unifieddiff.UnifiedDiffReader
import com.spotify.github.v3.clients.GitDataClient
import com.spotify.github.v3.clients.GitHubClient
import com.spotify.github.v3.clients.RepositoryClient
import com.spotify.github.v3.exceptions.RequestNotOkException
import com.spotify.github.v3.prs.PullRequest
import com.spotify.github.v3.prs.requests.ImmutablePullRequestCreate
import com.spotify.github.v3.repos.Branch
import com.spotify.github.v3.repos.Content
import com.spotify.github.v3.repos.Repository
import com.spotify.github.v3.repos.requests.ImmutableFileCreate
import com.spotify.github.v3.repos.requests.ImmutableFileUpdate
import com.spotify.github.v3.search.requests.ImmutableSearchParameters
import com.spotify.githubclient.shade.okhttp3.OkHttpClient
import java.io.File
import java.net.URI
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

fun main(args: Array<String>) = Patchinator().main(args)

class Patchinator : CliktCommand() {

  private val token by mutuallyExclusiveOptions(
      option("--token", help = "GitHub token to use"),
      option("--token-variable", help = "Name of an environment variable to read GitHub token from").convert { System.getenv(it) }
  ).required()

  private val organization by option("-o", "--org", help = "GitHub organization to patch").required()

  private val searchQuery by option(
    "-q",
    "--query",
    help = "Search query for repositories. For example, prefix of repository name."
  ).default("")

  private val queryLimit by option(
    "-n",
    "--limit",
    help = "Maximum number of repositories to be returned from the query. Defaults to 30."
  ).convert { it.toInt() }.default(30)

  private val patch by option("-p", "--patch", help = "Path to patch to apply").convert { File(it) }.required()

  private val debug by option("--debug", help = "Enables additional output").flag()

  private val commitMessage by option("-m", "--message", help = "Commit message to create a commit based on the patch").required()

  private val branchName by option("--branch", help = "Branch name to create for the change").defaultLazy  { commitMessage.replace(" ", "-") }

  override fun run() {
    withHttpClient(this::patch)
  }

  private fun patch(httpClient: OkHttpClient) {
    val githubClient = GitHubClient.create(httpClient, URI.create("https://api.github.com/"), token)

    val repos = githubClient.createSearchClient().repositories(
      ImmutableSearchParameters.builder()
        .q("org:$organization $searchQuery")
        .per_page(queryLimit)
        .build()
    ).get(5, TimeUnit.SECONDS).items()!!

    promptForSelectedRepos(repos).forEach {
      debug("Patching ${it.name()}")
      val pr = patchRepository(githubClient, it)
      println("PR for patching ${it.name()}: ${pr.url()}")

    }
  }

  private fun promptForSelectedRepos(repos: List<Repository>): List<Repository> {
    val sortedRepos = repos.sortedBy { it.name() }
    val areReposSelected = editText(
      "Select repositories to patch (with 'X'):\n" +
          sortedRepos.joinToString(separator = "\n") { "[] ${it.name()}" },
    )!!.split("\n").drop(1).filter { it.isNotBlank() }.map {
      if (it.startsWith("[X]")) {
        true
      } else if (it.startsWith("[]")) {
        false
      } else {
        throw IllegalStateException("Invalid input: $it")
      }
    }

    return sortedRepos.zip(areReposSelected).filter { (_, selected) -> selected }.map { (repo, _) -> repo }
  }

  private fun patchRepository(githubClient: GitHubClient, repo: Repository): PullRequest {
    val repoClient = githubClient.createRepositoryClient(organization, repo.name())

    val dataClient = githubClient.createGitDataClient(organization, repo.name())

    val mainSha = dataClient.getBranchReference(repo.defaultBranch()).get(5, TimeUnit.SECONDS).`object`()!!.sha()!!

    val branch = ensureBranchExists(repoClient, dataClient, mainSha)

    val parsedDiff = UnifiedDiffReader.parseUnifiedDiff(patch.inputStream())
    parsedDiff.files.forEach {
      commitFile(repoClient, branch, it)
    }

    return repoClient.createPullRequestClient().create(
      ImmutablePullRequestCreate.builder()
        .title(commitMessage)
        .head(branch.name()!!)
        .base(repo.defaultBranch())
        .body("Automated by Patchinator")
        .build()
    ).get(5, TimeUnit.SECONDS)
  }

  private fun commitFile(repoClient: RepositoryClient, branch: Branch,  fileDiff: UnifiedDiffFile) {
    if (fileDiff.toFile == null) {
      deleteFile(repoClient, branch, fileDiff.fromFile)
    }
    else {
      upsertFile(repoClient, branch, fileDiff)
    }
  }

  private fun upsertFile(repoClient: RepositoryClient, branch: Branch, fileDiff: UnifiedDiffFile) {
    if (fileDiff.fromFile == null || fileDiff.fromFile == "/dev/null") {
      createFile(repoClient, branch, fileDiff)
    }
    else {
      updateFile(repoClient, branch, fileDiff)
    }
  }

  @OptIn(ExperimentalEncodingApi::class)
  private fun createFile(repoClient: RepositoryClient, branch: Branch, fileDiff: UnifiedDiffFile) {
    val newFile = fileDiff.patch.applyTo(listOf()).joinToString(separator = "\n")

    debug("New file: $newFile")
    repoClient.createFileContent(
      fileDiff.toFile, ImmutableFileCreate.builder()
        .content(Base64.encode(newFile.toByteArray()))
        .message(commitMessage)
        .branch(branch.name()!!)
        .build()
    ).get(5, TimeUnit.SECONDS)
  }

  @OptIn(ExperimentalEncodingApi::class)
  private fun updateFile(repoClient: RepositoryClient, branch: Branch, fileDiff: UnifiedDiffFile) {
    val file = getFileContentFromRepo(repoClient, fileDiff.fromFile!!)!!
    val decodedContent = String(Base64.decode(file.content()!!.filterNot(Char::isWhitespace)), Charsets.UTF_8)
    debug("Existing file: $decodedContent")

    val patched = fileDiff.patch.applyTo(decodedContent.split("\n")).joinToString(separator = "\n")
    debug("Patched file: $patched")

    repoClient.updateFileContent(
      fileDiff.toFile, ImmutableFileUpdate.builder()
        .content(Base64.encode(patched.toByteArray()))
        .message(commitMessage)
        .sha(file.sha()!!)
        .branch(branch.name()!!)
        .build()
    ).get(5, TimeUnit.SECONDS)
  }

  private fun deleteFile(repoClient: RepositoryClient, branch: Branch, fileName: String): Unit =
    throw IllegalStateException("Deleting not implemented")

  private fun getFileContentFromRepo(
    repoClient: RepositoryClient,
    filePath: String
  ): Content? = try {
    repoClient.getFileContent(filePath).get(5, TimeUnit.SECONDS)
  }
  catch (e: RequestNotOkException) {
    if (e.statusCode() == 404) {
      null
    } else {
      throw e
    }
  }

  private fun ensureBranchExists(repoClient: RepositoryClient, dataClient: GitDataClient, mainSha: String): Branch =
    try {
      repoClient.getBranch(branchName).get(5, TimeUnit.SECONDS)
    }
    catch (e: ExecutionException) {
      when (val cause = e.cause) {
        is RequestNotOkException -> {
          if (cause.statusCode() != 404) {
            throw e
          }
          echo("Branch $branchName does not exist, creating")
          dataClient.createBranchReference(branchName, mainSha).get(5, TimeUnit.SECONDS)
          repoClient.getBranch(branchName).get(5, TimeUnit.SECONDS)
        }
        else -> throw e
      }
    }

  private fun withHttpClient(task: (OkHttpClient) -> Unit) {
    val httpClient = OkHttpClient()
    try {
      task(httpClient)
    } finally {
      httpClient.let(Accessor::getDispatcher).let(Accessor::getExecutorService).shutdown()
      httpClient.let(Accessor::getConnectionPool).evictAll()
      httpClient.let(Accessor::getCache)?.close()
    }
  }

  private fun debug(message: String) = takeIf { debug }?.let { echo(message) }

}
