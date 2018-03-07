package cromwell.docker.registryv2.flows.gcr

import akka.actor.Scheduler
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.stream.ActorMaterializer
import com.google.auth.oauth2.OAuth2Credentials
import cromwell.cloudsupport.gcp.auth.GoogleAuthMode
import cromwell.docker.DockerHashActor.DockerHashContext
import cromwell.docker.registryv2.DockerRegistryV2AbstractFlow
import cromwell.docker.registryv2.DockerRegistryV2AbstractFlow.HttpDockerFlow

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

abstract class GcrAbstractFlow(httpClientFlow: HttpDockerFlow, host: String)(implicit ec: ExecutionContext, materializer: ActorMaterializer, scheduler: Scheduler) extends DockerRegistryV2AbstractFlow(httpClientFlow)(ec, materializer, scheduler) {
  
  private val AccessTokenAcceptableTTL = 1.minute
  
  override val registryHostName = host
  override val authorizationServerHostName = s"$host/v2"
  
  /**
    * Builds the list of headers for the token request
    */
   def buildTokenRequestHeaders(dockerHashContext: DockerHashContext) = {
    dockerHashContext.credentials collect {
      case credentials: OAuth2Credentials =>
        val token = GoogleAuthMode.freshAccessToken(AccessTokenAcceptableTTL, credentials)
        Authorization(OAuth2BearerToken(token))
    }
  }
}
