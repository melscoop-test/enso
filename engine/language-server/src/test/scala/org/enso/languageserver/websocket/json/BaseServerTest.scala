package org.enso.languageserver.websocket.json

import akka.testkit.TestProbe
import io.circe.literal._
import org.apache.commons.io.FileUtils
import org.enso.jsonrpc.test.JsonRpcServerTestKit
import org.enso.jsonrpc.{ClientControllerFactory, Protocol}
import org.enso.languageserver.boot.resource.{
  DirectoriesInitialization,
  RepoInitialization,
  SequentialResourcesInitialization
}
import org.enso.languageserver.capability.CapabilityRouter
import org.enso.languageserver.data._
import org.enso.languageserver.effect.ZioExec
import org.enso.languageserver.event.InitializedEvent
import org.enso.languageserver.filemanager.{
  ContentRootManager,
  ContentRootManagerActor,
  ContentRootManagerWrapper,
  ContentRootType,
  ContentRootWithFile,
  FileManager,
  FileSystem,
  ReceivesTreeUpdatesHandler
}
import org.enso.languageserver.io._
import org.enso.languageserver.protocol.json.{
  JsonConnectionControllerFactory,
  JsonRpc
}
import org.enso.languageserver.refactoring.ProjectNameChangedEvent
import org.enso.languageserver.runtime.{ContextRegistry, RuntimeFailureMapper}
import org.enso.languageserver.search.SuggestionsHandler
import org.enso.languageserver.session.SessionRouter
import org.enso.languageserver.text.BufferRegistry
import org.enso.polyglot.data.TypeGraph
import org.enso.polyglot.runtime.Runtime.Api
import org.enso.searcher.sql.{SqlDatabase, SqlSuggestionsRepo, SqlVersionsRepo}
import org.enso.text.Sha3_224VersionCalculator

import java.nio.file.Files
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration._
import io.circe.parser.parse
import io.circe.syntax.EncoderOps
import org.enso.testkit.EitherValue
import org.scalatest.OptionValues

class BaseServerTest
    extends JsonRpcServerTestKit
    with EitherValue
    with OptionValues {

  import system.dispatcher

  val timeout: FiniteDuration = 10.seconds

  val testContentRootId = UUID.randomUUID()
  val testContentRoot = ContentRootWithFile(
    testContentRootId,
    ContentRootType.Project,
    "Project",
    Files.createTempDirectory(null).toRealPath().toFile
  )
  val config                = mkConfig
  val runtimeConnectorProbe = TestProbe()
  val versionCalculator     = Sha3_224VersionCalculator

  val typeGraph: TypeGraph = {
    val graph = TypeGraph("Any")
    graph.insert("Number", "Any")
    graph.insert("Integer", "Number")
    graph
  }

  sys.addShutdownHook(FileUtils.deleteQuietly(testContentRoot.file))

  def mkConfig: Config =
    Config(
      testContentRoot,
      FileManagerConfig(timeout = 3.seconds),
      PathWatcherConfig(),
      ExecutionContextConfig(requestTimeout = 3.seconds),
      ProjectDirectoriesConfig(testContentRoot.file)
    )

  override def protocol: Protocol = JsonRpc.protocol

  val stdOut    = new ObservableOutputStream
  val stdErr    = new ObservableOutputStream
  val stdInSink = new ObservableOutputStream
  val stdIn     = new ObservablePipedInputStream(stdInSink)

  val sessionRouter =
    system.actorOf(SessionRouter.props())

  val stdOutController =
    system.actorOf(
      OutputRedirectionController
        .props(stdOut, OutputKind.StandardOutput, sessionRouter)
    )

  val stdErrController =
    system.actorOf(
      OutputRedirectionController
        .props(stdErr, OutputKind.StandardError, sessionRouter)
    )

  val stdInController =
    system.actorOf(
      InputRedirectionController.props(stdIn, stdInSink, sessionRouter)
    )

  val zioExec = ZioExec(zio.Runtime.default)
  val sqlDatabase =
    SqlDatabase(config.directories.suggestionsDatabaseFile.toString)
  val suggestionsRepo = new SqlSuggestionsRepo(sqlDatabase)(system.dispatcher)
  val versionsRepo    = new SqlVersionsRepo(sqlDatabase)(system.dispatcher)

  val initializationComponent = SequentialResourcesInitialization(
    new DirectoriesInitialization(config.directories),
    new RepoInitialization(
      config.directories,
      system.eventStream,
      suggestionsRepo,
      versionsRepo
    )
  )

  val contentRootManagerActor =
    system.actorOf(ContentRootManagerActor.props(config))

  override def clientControllerFactory: ClientControllerFactory = {
    val contentRootManagerWrapper: ContentRootManager =
      new ContentRootManagerWrapper(config, contentRootManagerActor)
    val fileManager = system.actorOf(
      FileManager.props(
        config.fileManager,
        contentRootManagerWrapper,
        new FileSystem,
        zioExec
      )
    )
    val bufferRegistry =
      system.actorOf(
        BufferRegistry.props(
          versionsRepo,
          fileManager,
          runtimeConnectorProbe.ref
        )(
          Sha3_224VersionCalculator
        )
      )
    val fileEventRegistry = system.actorOf(
      ReceivesTreeUpdatesHandler.props(
        config,
        contentRootManagerWrapper,
        new FileSystem,
        zioExec
      )
    )

    val contextRegistry =
      system.actorOf(
        ContextRegistry.props(
          suggestionsRepo,
          config,
          RuntimeFailureMapper(contentRootManagerWrapper),
          runtimeConnectorProbe.ref,
          sessionRouter
        )
      )

    val suggestionsHandler =
      system.actorOf(
        SuggestionsHandler.props(
          config,
          contentRootManagerWrapper,
          suggestionsRepo,
          versionsRepo,
          sessionRouter,
          runtimeConnectorProbe.ref
        )
      )

    val capabilityRouter =
      system.actorOf(
        CapabilityRouter.props(
          bufferRegistry,
          fileEventRegistry,
          suggestionsHandler
        )
      )

    // initialize
    suggestionsHandler ! InitializedEvent.TruffleContextInitialized
    runtimeConnectorProbe.receiveN(1)
    suggestionsHandler ! Api.Response(
      UUID.randomUUID(),
      Api.GetTypeGraphResponse(typeGraph)
    )
    Await.ready(initializationComponent.init(), timeout)
    system.eventStream.publish(ProjectNameChangedEvent("Test", "Test"))
    runtimeConnectorProbe.receiveN(1)
    suggestionsHandler ! Api.Response(
      UUID.randomUUID(),
      Api.VerifyModulesIndexResponse(Seq())
    )

    new JsonConnectionControllerFactory(
      initializationComponent,
      bufferRegistry,
      capabilityRouter,
      fileManager,
      contentRootManagerActor,
      contextRegistry,
      suggestionsHandler,
      stdOutController,
      stdErrController,
      stdInController,
      runtimeConnectorProbe.ref,
      config
    )
  }

  def getInitialisedWsClient(): WsTestClient = {
    val client = new WsTestClient(address)
    initSession(client)
    client
  }

  private def initSession(client: WsTestClient): UUID = {
    val clientId = UUID.randomUUID()
    client.send(json"""
          { "jsonrpc": "2.0",
            "method": "session/initProtocolConnection",
            "id": 1,
            "params": {
              "clientId": $clientId
            }
          }
          """)
    val response = parse(client.expectMessage()).rightValue.asObject.value
    response("jsonrpc") shouldEqual Some("2.0".asJson)
    response("id") shouldEqual Some(1.asJson)
    val result = response("result").value.asObject.value
    result("contentRoots").value.asArray.value should contain(
      json"""
          {
            "id" : $testContentRootId,
            "type" : "Project",
            "name" : "Project"
          }
          """
    )
    clientId
  }

}
