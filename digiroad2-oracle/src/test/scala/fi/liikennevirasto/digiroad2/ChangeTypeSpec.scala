package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.client.kmtk.{ChangeInfo, ChangeType}
import org.scalatest.{FunSuite, Matchers}

/**
  * Created by venholat on 23.5.2016.
  */
class ChangeTypeSpec extends FunSuite with Matchers {

  private def allClasses(changeInfo: ChangeInfo): Seq[Boolean] = {
    Seq(ChangeType.isCreationChange(changeInfo),
      ChangeType.isReplacementChange(changeInfo),
      ChangeType.isExtensionChange(changeInfo),
      ChangeType.isUnknownChange(changeInfo),
      ChangeType.isRemovalChange(changeInfo)
    )
  }

  test("Test partition of changes When each group of different type changes should belong to one category type") {
    ChangeType.values.map(v => ChangeInfo(None, None, 1L, ChangeType.apply(v.value), None, None, None, None)).foreach(ci => {
      val (group, othergroups) = allClasses(ci).partition(b => b)
      group.size should be (1)
      othergroups.size should be (4)
    })
  }
}
