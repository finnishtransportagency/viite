package fi.liikennevirasto.viite.process

class InvalidAddressDataException(string: String) extends RuntimeException {
  override def getMessage: String = string
};
