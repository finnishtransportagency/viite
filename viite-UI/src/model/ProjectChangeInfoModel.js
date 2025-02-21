(function (root) {
  root.ProjectChangeInfoModel = function (backend) {
    var addrMRange = [{
      start:0,
      end: 0
    }];
    var roadInfoList = [{ // TODO refactor field order, this is dumb order.
      endRoadPartNumber: 0,
      roadNumber: 0,
      addrMRange: addrMRange,
      startRoadPartNumber: 0,
      trackCode: 0
    }];
    var changesInfo = [{
      changetype: 0,
      discontinuity: "jatkuva",
      administrativeClass: 9,
      source: roadInfoList,
      target: roadInfoList,
      reversed: false
    }];
    var changeTable = {
      id: 0,
      name: "templateproject",
      user: "templateuser",
      changeDate: "1980-01-28",
      changeInfoSeq: changesInfo
    };
    var projectChanges = {changeTable: changeTable};

    function loadChanges() {
      var warningM = projectChanges.warningMessage;
      if (!_.isUndefined(warningM))
        new ModalConfirm(warningM);
      if (!_.isUndefined(projectChanges) && projectChanges.discontinuity !== null) {
        eventbus.trigger('projectChanges:fetched', projectChanges);
      }
    }

    function getChanges(projectID, sortFn) {
      applicationModel.addSpinner();
      backend.getChangeTable(projectID, function (changeData) {
        roadChangeAPIResultParser(changeData);
        sortFn();
        loadChanges();
        applicationModel.removeSpinner();
      });
    }

    function sortChanges(side, reverse) {
      projectChanges.changeTable.changeInfoSeq = _.sortBy(projectChanges.changeTable.changeInfoSeq,
        [side + ".roadNumber", side + ".startRoadPartNumber", side + ".startAddressM", side + ".trackCode"]);
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
  };
}(this));
