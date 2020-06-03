package fi.liikennevirasto.digiroad2.authentication

import org.scalatest.{FunSuite, Matchers}

class JWTReaderSpec extends FunSuite with Matchers {

  test("Test parseUsernameFromJWTPayloadJSONString When valid JSON Then return username") {
    val json = """{"custom:rooli":"int_kayttajat,Extranet_Kayttaja,arn:aws:iam::117531223221:role/ViiteAdmin\\,arn:aws:iam::117531223221:saml-provider/VaylaTestOAM","sub":"2b5a2b65-ca06-46e2-8a52-a5190b495d12","email_verified":"false","custom:uid":"K567997","email":"sami.kosonen@cgi.com","username":"vaylatestoam_sami.kosonen@cgi.com","exp":1591117019,"iss":"https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_oNzPsiXEJ"}"""
    JWTReader.parseUsernameFromJWTPayloadJSONString(json) should be("K567997")
  }

}
