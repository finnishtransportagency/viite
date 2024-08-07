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
import scala.util.{Failure, Random, Success}

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
    * When using avoidRestrictions, do not hide errors but delegate these to avoidRestrictions method.
    * API Gateway timeouts if response is not received in 30 sec
    *  -> Return redirect to same url with retry param
    *  -> Save response to S3 when it is ready (access with pre-signed url)
    */
  def avoidRestrictions[T](requestId: String, request: HttpServletRequest, params: Params,
                           responseType: String = "json")(f: Params => T): Any = {

    // In case we are not using AWS in the current environment, just run the API request
    if (!ViiteProperties.awsConnectionEnabled)
      return f(params)

    logger.info(s"API LOG: ${request.getQueryString} in avoidRestrictions! (circumventing API time and size restrictions)--")
    val queryString = if (request.getQueryString != null) s"?${request.getQueryString}" else ""
    val path = "/viite" + request.getRequestURI + queryString
    val workId = getWorkId(requestId, params, responseType) // Used for naming the resulting s3 object
    val queryId = params.get("queryId") match {             // Used for identifying log lines for the same query
      case Some(id) => id
      case None =>
        val id = Integer.toHexString(new Random().nextInt)
        logger.info(s"API LOG $id: Received query $path at ${DateTime.now}")
        id
    }

    val objectExists = s3Service.isS3ObjectAvailable(s3Bucket, workId, 2, Some(objectTTLSeconds))

    (params.get("retry"), objectExists) match {
      case (_, true) => // the result is ready, make the last redirect to the resulting file
        val preSignedUrl = s3Service.getPreSignedUrl(s3Bucket, workId)
        logger.info(s"In avoidRestrictions, last REDIRECT TO THE RESULT. preSignedUrl: $preSignedUrl--")
        redirectToUrl(preSignedUrl, queryId)

      case (None, false) => // first call; get the API call running
        newQuery(workId, queryId, path, f, params, responseType)

      case (Some(retry: String), false) => // otherwise, go to the redirect loop (or decide we have tried too many times)
        val currentRetry = retry.toInt
        if (currentRetry <= MAX_RETRIES)
          redirectBasedOnS3ObjectExistence(workId, queryId, path, currentRetry)
        else {
          logger.info(s"API LOG $queryId: Maximum retries reached. Unable to respond to query.")
          BadRequest(s"Request with id $queryId failed. Maximum retries reached. Unable to get object.")
        }
    }
  }

  /** Work id formed of request id (i.e. "integration") and query params */
  def getWorkId(requestId: String, params: Params, contentType: String): String = {
    val sortedParams = params.toMap.toSeq.filterNot(param => param._1 == "retry" || param._1 == "queryId").sortBy(_._1)
    val identifiers = Seq(requestId) ++ sortedParams.map(_._2.replaceAll(",", "-"))
    s"${identifiers.mkString("_")}.$contentType"
  }

  /** Get the API request running, and return with an initial redirect address, with retry count 1.
    * The API request returns in the future, and the result gets saved into s3) */
  def newQuery[T](workId: String, queryId: String, path: String, f: Params => T, params: Params, responseType: String): Any = {
    var finished: Any = Unit
    Future { // Complete query and save results to s3 in future
      logger.info(s"We are in the future! (newQuery for queryId = $queryId)--")
      finished = f(params)
    }.onComplete {
      case Failure(e) => { // The Future ended up in an exception
        val throwableInfo: String = { if(e.isInstanceOf[Exception]) { e.getMessage } else { e.getClass.toString } }
        logger.error(s"API LOG $queryId: Future completed with failure: $finished ($throwableInfo)")
        //logger.error(s"API LOG $queryId: error ${e.getClass} with message ${e.getMessage} and stacktrace: \n ${e.getStackTrace.mkString("", EOL, EOL)}")

        // Save the error message to S3, so that the caller gets the error feedback from there, ...
        // ... and does not stay stuck waiting forever in the case of a failure.
        // (Tried to get that work with "more beautiful" ways, but did not succeed. ...
        // ... But if you can, make it happen. :) )
        val errorResponseBody = e.getMessage
        s3Service.saveFileToS3(s3Bucket, workId, errorResponseBody, "text/plain")
      }
      case Success(t) => {
        logger.info(s"API LOG $queryId: Future completed successfully. Returning $finished as $responseType")
        val responseBody = formatResponse(finished, responseType)
        s3Service.saveFileToS3(s3Bucket, workId, responseBody, responseType)
      }
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
      case (response: ActionResult, "json") => // ActionResult case: for Exception handling
        response.body.toString
      case _ =>
        throw new NotImplementedError("Unrecognized response format")
    }
  }

  /** Return with HTTP redirect. Add to the <i>path</i> URL a retry param, grow it, or redirect to the address of a result file. */
  def redirectToUrl(path: String, queryId: String, nextRetry: Option[Int] = None): ActionResult = {
    nextRetry match {
      case Some(retryValue) if retryValue == 1 =>
        val paramSeparator = if (path.contains("?")) "&" else "?"
        logger.info(s"API LOG $queryId: Redirecting first time at ${DateTime.now}")
        Found.apply(path + paramSeparator + s"queryId=$queryId&retry=$retryValue")
      case Some(retryValue) if retryValue > 1 =>
        val newPath = path.replaceAll("""retry=\d+""", s"retry=$retryValue")
        logger.info(s"API LOG $queryId: Redirecting again at ${DateTime.now}")

        Found.apply(newPath)
      case _ =>
        logger.info(s"API LOG $queryId: Completed the query at ${DateTime.now}")
        Found.apply(path)
    }
  }

  /** Poll (recursively) the availability of the result file, while within the given <i>timeToQuery</i>.
    * @return true, if the result file is available. False, if the time limit has been exceeded. */
  @tailrec
  def objectAvailableInS3(workId: String, timeToQuery: Long): Boolean = {
    val startTime = System.currentTimeMillis()
    val s3ObjectAvailable = s3Service.isS3ObjectAvailable(s3Bucket, workId, timeToQuery, Some(objectTTLSeconds))
    if (s3ObjectAvailable)
      true
    else {
      val endTime = System.currentTimeMillis()
      val timeLeft = timeToQuery - (endTime - startTime)
      val millisToNextQuery = 2000
      if (timeLeft > millisToNextQuery) { // loop recursively while we haven't exceeded the given time limit
        Thread.sleep(millisToNextQuery)
        objectAvailableInS3(workId, timeLeft - millisToNextQuery)
      }
      else // took too long; return false
        false
    }
  }

  /** Redirect to the result file, if it is available.
    * Otherwise, grow the redirect address retry count by one, and redirect there. */
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
