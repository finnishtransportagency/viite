/**
 * ProjectChangeInfoModel - Handles project change tracking and history
 * 
 * Manages project change information including:
 * - Change table data and metadata
 * - Road address change tracking
 * - Change type classification and sorting
 * - Backend integration for change history
 * - Warning message handling for changes
 */


export function ProjectChangeInfoModel(backend) {
    const addrMRange = [{
      start:0,
      end: 0
    }];
    const roadInfoList = [{ // TODO refactor field order, this is dumb order.
      endRoadPartNumber: 0,
      roadNumber: 0,
      addrMRange: addrMRange,
      startRoadPartNumber: 0,
      trackCode: 0
    }];
    const changesInfo = [{
      changetype: 0,
      discontinuity: "jatkuva",
      administrativeClass: 9,
      source: roadInfoList,
      target: roadInfoList,
      reversed: false
    }];
    const changeTable = {
      id: 0,
      name: "templateproject",
      user: "templateuser",
      changeDate: "1980-01-28",
      changeInfoSeq: changesInfo
    };
    let projectChanges = {changeTable: changeTable};

    function getChanges(projectID, sortFn, onComplete) {
      backend.getChangeTable(projectID, function (changeData) {
        roadChangeAPIResultParser(changeData);
        sortFn();
        onComplete(projectChanges);
      });
    }

    function sortChanges(side, reverse) {
      projectChanges.changeTable.changeInfoSeq = _.sortBy(projectChanges.changeTable.changeInfoSeq,
        [side + ".roadNumber", side + ".startRoadPartNumber", side + ".addrMRange.start", side + ".trackCode"]);
      if (reverse) projectChanges.changeTable.changeInfoSeq.reverse();
      return projectChanges;
    }

    function roadChangeAPIResultParser(changeData) {
      projectChanges = changeData;
    }

    return {
      getChanges: getChanges,
      sortChanges: sortChanges
    };
}
