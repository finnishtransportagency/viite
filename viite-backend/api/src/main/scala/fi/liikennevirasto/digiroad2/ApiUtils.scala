package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.Digiroad2Context.awsService
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import org.joda.time.DateTime

import java.util.concurrent.TimeUnit
import javax.servlet.http.HttpServletRequest
import org.json4s.DefaultFormats
import org.json4s.native.Json
import org.scalatra.{ActionResult, BadRequest, Found, Params}
import org.slf4j.{Logger, LoggerFactory}

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

object ApiUtils {
  val logger: Logger = LoggerFactory.getLogger(getClass)
  lazy val s3Service: awsService.S3.type = awsService.S3
  val s3Bucket: String = ViiteProperties.apiS3BucketName
  val objectTTLSeconds: Int =
    if (ViiteProperties.apiS3ObjectTTLSeconds != null) ViiteProperties.apiS3ObjectTTLSeconds.toInt
    else 300

  val MAX_WAIT_TIME_SECONDS: Int = 20
  val MAX_RETRIES: Int = 540 // 3 hours / 20sec per retry

  /**
    * Avoid API Gateway restrictions
    * API Gateway timeouts if response is not received in 30 sec
    *  -> Return redirect to same url with retry param
    *  -> Save response to S3 when its ready (access with pre-signed url)
    */
  def avoidRestrictions[T](requestId: String, request: HttpServletRequest, params: Params,
                           responseType: String = "json")(f: Params => T): Any = {
    if (!ViiteProperties.awsConnectionEnabled) return f(params)
    val queryString = if (request.getQueryString != null) s"?${request.getQueryString}" else ""
    val path = "/viite" + request.getRequestURI + queryString
    val workId = getWorkId(requestId, params, responseType) // Used to name s3 objects
    val queryId = params.get("queryId") match {             // Used to identify requests in logs
      case Some(id) => id
      case None =>
        val id = Integer.toHexString(new Random().nextInt)
        logger.info(s"API LOG $id: Received query $path at ${DateTime.now}")
        id
    }

    val objectExists = s3Service.isS3ObjectAvailable(s3Bucket, workId, 2, Some(objectTTLSeconds))

    (params.get("retry"), objectExists) match {
      case (_, true) =>
        val preSignedUrl = s3Service.getPreSignedUrl(s3Bucket, workId)
        redirectToUrl(preSignedUrl, queryId)

      case (None, false) =>
        newQuery(workId, queryId, path, f, params, responseType)

      case (Some(retry: String), false) =>
        val currentRetry = retry.toInt
        if (currentRetry <= MAX_RETRIES)
          redirectBasedOnS3ObjectExistence(workId, queryId, path, currentRetry)
        else {
          logger.info(s"API LOG $queryId: Maximum retries reached. Unable to respond to query.")
          BadRequest("Maximum retries reached. Unable to get object.")
        }
    }
  }

  /** Work id formed of request id (i.e. "integration") and query params */
  def getWorkId(requestId: String, params: Params, contentType: String): String = {
    val sortedParams = params.toSeq.filterNot(param => param._1 == "retry" || param._1 == "queryId").sortBy(_._1)
    val identifiers = Seq(requestId) ++ sortedParams.map(_._2.replaceAll(",", "-"))
    s"${identifiers.mkString("_")}.$contentType"
  }

  def newQuery[T](workId: String, queryId: String, path: String, f: Params => T, params: Params, responseType: String): Any = {
    Future { // Complete query and save results to s3 in future
      val finished = f(params)
      val responseBody = formatResponse(finished,  responseType)
      s3Service.saveFileToS3(s3Bucket, workId, responseBody, responseType)
    }
    redirectToUrl(path, queryId, Some(1))
  }

  def formatResponse(content: Any, responseType: String): String = {
    (content, responseType) match {
      case (response: Seq[_], "json") =>
        Json(DefaultFormats).write(response.asInstanceOf[Seq[Map[String, Any]]])
      case (response: Set[_], "json") =>
        Json(DefaultFormats).write(response.asInstanceOf[Set[Map[String, Any]]])
      case (response: Map[_, _], "json") =>
        Json(DefaultFormats).write(response.asInstanceOf[Map[String, Any]])
      case _ =>
        throw new NotImplementedError("Unrecognized response format")
    }
  }

  def redirectToUrl(path: String, queryId: String, nextRetry: Option[Int] = None): ActionResult = {
    nextRetry match {
      case Some(retryValue) if retryValue == 1 =>
        val paramSeparator = if (path.contains("?")) "&" else "?"
        Found.apply(path + paramSeparator + s"queryId=$queryId&retry=$retryValue")
      case Some(retryValue) if retryValue > 1 =>
        val newPath = path.replaceAll("""retry=\d+""", s"retry=$retryValue")
        Found.apply(newPath)
      case _ =>
        logger.info(s"API LOG $queryId: Completed the query at ${DateTime.now}")
        Found.apply(path)
    }
  }

  @tailrec
  def objectAvailableInS3(workId: String, timeToQuery: Long): Boolean = {
    val startTime = System.currentTimeMillis()
    val s3ObjectAvailable = s3Service.isS3ObjectAvailable(s3Bucket, workId, timeToQuery, Some(objectTTLSeconds))
    if (s3ObjectAvailable) true
    else {
      val endTime = System.currentTimeMillis()
      val timeLeft = timeToQuery - (endTime - startTime)
      val millisToNextQuery = 2000
      if (timeLeft > millisToNextQuery) {
        Thread.sleep(millisToNextQuery)
        objectAvailableInS3(workId, timeLeft - millisToNextQuery)
      } else false
    }
  }

  def redirectBasedOnS3ObjectExistence(workId: String, queryId: String, path: String, currentRetry: Int): ActionResult = {
    // If object exists in s3, returns pre-signed url otherwise redirects to same url with incremented retry param
    val s3ObjectAvailable = objectAvailableInS3(workId, TimeUnit.SECONDS.toMillis(MAX_WAIT_TIME_SECONDS))
    if (s3ObjectAvailable) {
      val preSignedUrl = s3Service.getPreSignedUrl(s3Bucket, workId)
      redirectToUrl(preSignedUrl, queryId)
    } else {
      redirectToUrl(path, queryId, Some(currentRetry + 1))
    }
  }
}