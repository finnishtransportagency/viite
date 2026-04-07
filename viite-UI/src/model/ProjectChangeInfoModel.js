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
import { eventbus } from '@utils/eventbus.js';
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';

export function ProjectChangeInfoModel(backend, applicationModel) {
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

    function loadChanges() {
      const warningM = projectChanges.warningMessage;
      if (!_.isUndefined(warningM))
        new ConfirmPopup(warningM, { type: "alert" });
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
