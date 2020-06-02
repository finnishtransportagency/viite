package fi.liikennevirasto.digiroad2.authentication

import org.scalatest.{FunSuite, Matchers}
import pdi.jwt.{Jwt, JwtAlgorithm}

// TODO Prepare proper private and public keys https://kjur.github.io/jsjws/tool_jwt.html
class JWTReaderSpec extends FunSuite with Matchers {

  val secretKey = "secretKeySecretKey"

  test("Test getUsername When empty Then is failure.") {
    JWTReader.decodeToken("", secretKey).isFailure should be(true)
  }

  test("Test getUsername When wrong secret key Then is failure.") {
    val token = Jwt.encode("""{"user":1}""", "anotherSecretKey", JwtAlgorithm.RS256)
    assertThrows[java.security.spec.InvalidKeySpecException] {
      JWTReader.decodeToken(token, secretKey).isFailure should be(true)
    }
  }

  test("Test getUsername When wrong algorithm Then is failure.") {
    val token = Jwt.encode("""{"user":1}""", secretKey, JwtAlgorithm.RS384)
    assertThrows[java.lang.IllegalArgumentException] {
      JWTReader.decodeToken(token, secretKey).isFailure should be(true)
    }
  }

  test("Test getUsername When token given Then return user.") {
    val token = Jwt.encode("""{"user":"test"}""", secretKey, JwtAlgorithm.RS256)
    JWTReader.decodeToken(token, secretKey) should be("""{"user":"test"}""")
  }

  test("Test getAccessToken When header given Then return access token") {
    val header = "eyJraWQiOiJaK3R2MTBSQXNDcnRjU2U0YTlUR3NPTTdHc3ltMEU2VUwzT3JLZm9BYVFrPSIsImFsZyI6IlJTMjU2In0.eyJzdWIiOiIyYjVhMmI2NS1jYTA2LTQ2ZTItOGE1Mi1hNTE5MGI0OTVkMTIiLCJjb2duaXRvOmdyb3VwcyI6WyJldS13ZXN0LTFfb056UHNpWEVKX1ZheWxhVGVzdE9BTSJdLCJ0b2tlbl91c2UiOiJhY2Nlc3MiLCJzY29wZSI6Im9wZW5pZCIsImF1dGhfdGltZSI6MTU5MDc1OTY4NywiaXNzIjoiaHR0cHM6XC9cL2NvZ25pdG8taWRwLmV1LXdlc3QtMS5hbWF6b25hd3MuY29tXC9ldS13ZXN0LTFfb056UHNpWEVKIiwiZXhwIjoxNTkwNzYzMjg3LCJpYXQiOjE1OTA3NTk2ODcsInZlcnNpb24iOjIsImp0aSI6IjFmMTVkNzQyLTY4YzctNGExYS04ODNhLWIxNDg5YWYzY2I0OCIsImNsaWVudF9pZCI6IjNjdGMyMGQzaTRnaHY5NGtzMHNlbXQ0ZTE1IiwidXNlcm5hbWUiOiJ2YXlsYXRlc3RvYW1fc2FtaS5rb3NvbmVuQGNnaS5jb20ifQ.N4Qg2ZfKGlWFwGZprBp_l3tzHX22k3IGqgv00ZJJckPtXfo87t88kAA-5itJq0-JCsAT9fzc0-syuCx_WbngzKppHH_De6NuAkMZuFk22AsOvsqcGldsX2z-wu8VM_GAYmCVTLV6Yf_6zWT5VtoxY_8Ng-hqEWDKra4-9UD8_iORE3kRX1SM3UXREjZ5I9nCh-CKrem0ea0BWAb4pTyNmAFFf7WlcWvIuaJEd-KknVGKrHQDx0cB-aUv0kRN6DeakQX6OCqoMbPFRvRMmKJAKa5RIISFsTI7m_BUxQ6vmN7JsN6mVgcprcQHNtfymngg3KmGJfew-W-_Zinf1lhCLQ"
    val accessToken = JWTReader.getAccessToken(header)
    accessToken.alg should be("RS256")
    accessToken.kid should be("Z+tv10RAsCrtcSe4a9TGsOM7Gsym0E6UL3OrKfoAaQk=")
  }
}
