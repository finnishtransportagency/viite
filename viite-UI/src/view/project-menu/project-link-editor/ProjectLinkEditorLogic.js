import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';

export function createProjectLinkEditorLogic(dependencies) {
  const {
    RoadAddressChangeType,
    CalibrationCode,
    editableStatus,
    validEvks,
    formState,
    menuSelector = '#menu-container'
  } = dependencies;

  const transitionModifiers = (targetStatus, currentStatus) => {
    const mod = _.includes(targetStatus.transitionFrom, currentStatus) ? '' : 'disabled hidden';
    return currentStatus === targetStatus.value ? `${mod} selected` : mod;
  };

  const defineOptionModifiers = (option, selection) => {
    const roadAddressChangeType = selection[0].status;
    const targetRoadAddressChangeType = _.find(RoadAddressChangeType, ls => ls.description === option || (option === '' && ls.value === 99));
    return transitionModifiers(targetRoadAddressChangeType, roadAddressChangeType);
  };

  const isProjectEditable = (projectCollection) => {
    if (!projectCollection || !projectCollection.getCurrentProject()) return false;
    return _.includes(editableStatus, projectCollection.getCurrentProject().project.statusCode);
  };

  const checkInputs = (projectChangeTable) => {
    const rootElement = $(menuSelector);
    const inputs = rootElement.find('input');
    const pedestrianRoads = 70000;

    let filled = _.every(inputs, input => {
      if (input.type !== 'text') return true;
      if (input.value) return true;
      const isPedestrian = $('#tie')[0].value >= pedestrianRoads;
      return isPedestrian && input.id === 'roadName';
    });

    const trackCodeDropdown = $('#trackCodeDropdown')[0];
    filled = filled && trackCodeDropdown && trackCodeDropdown.value && trackCodeDropdown.value !== '99';

    const administrativeClassCodeDropdown = $('#administrativeClassDropdown')[0];
    filled = filled &&
      administrativeClassCodeDropdown &&
      administrativeClassCodeDropdown.value &&
      administrativeClassCodeDropdown.value !== '0' &&
      administrativeClassCodeDropdown.value !== '99';

    const updateButton = rootElement.find('.link-form button.update');
    updateButton.prop('disabled', !(filled && !projectChangeTable.isChangeTableOpen()));
  };

  const changeDropDownValue = (statusCode, selectedLinks) => {
    const dropdown_0_new = $(`#dropDown_0 option[value=${RoadAddressChangeType.New.description}]`);
    const rootElement = $(menuSelector);

    switch (statusCode) {
      case RoadAddressChangeType.Unchanged.value:
        dropdown_0_new.prop('disabled', true);
        $(`#dropDown_0 option[value=${RoadAddressChangeType.Unchanged.description}]`).attr('selected', 'selected').change();
        rootElement.find('#distanceValue').prop('hidden', true);
        break;
      case RoadAddressChangeType.New.value:
        dropdown_0_new.attr('selected', 'selected').change();
        rootElement.find('.new-road-address').prop('hidden', false);
        rootElement.find('#distanceValue').prop('hidden', false);
        if (selectedLinks[0].id !== 0) {
          rootElement.find('.changeDirectionDiv').prop('hidden', false);
        }
        break;
      case RoadAddressChangeType.Transfer.value:
        dropdown_0_new.prop('disabled', true);
        $(`#dropDown_0 option[value=${RoadAddressChangeType.Transfer.description}]`).attr('selected', 'selected').change();
        rootElement.find('.changeDirectionDiv').prop('hidden', true);
        rootElement.find('#distanceValue').prop('hidden', true);
        break;
      case RoadAddressChangeType.Numbering.value:
        $(`#dropDown_0 option[value=${RoadAddressChangeType.Numbering.description}]`).attr('selected', 'selected').change();
        rootElement.find('#distanceValue').prop('hidden', true);
        break;
      case RoadAddressChangeType.Terminated.value:
        $(`#dropDown_0 option[value=${RoadAddressChangeType.Terminated.description}]`).attr('selected', 'selected').change();
        rootElement.find('#distanceValue').prop('hidden', true);
        break;
      default:
        rootElement.find('#distanceValue').prop('hidden', true);
        break;
    }

    if (selectedLinks && selectedLinks.length > 0) {
      $('#discontinuityDropdown').val(selectedLinks[selectedLinks.length - 1].discontinuity);
    }
  };

  const fillDistanceValues = (selectedLinks, projectCollection) => {
    const beginDistance = $('#beginDistance');
    const endDistance = $('#endDistance');

    if (selectedLinks.length === 1 && selectedLinks[0].calibrationCode === CalibrationCode.AtBoth.value) {
      beginDistance.val(selectedLinks[0].addrMRange.start);
      if (isProjectEditable(projectCollection)) {
        endDistance.prop('readonly', false).val(selectedLinks[0].addrMRange.end);
      } else {
        endDistance.val(selectedLinks[0].addrMRange.end);
      }
    } else {
      const orderedByStartM = _.sortBy(selectedLinks, l => l.addrMRange.start);
      if (orderedByStartM[0].calibrationCode === CalibrationCode.AtBeginning.value) {
        beginDistance.val(orderedByStartM[0].addrMRange.start);
      }

      const lastLink = orderedByStartM[orderedByStartM.length - 1];
      if (lastLink.calibrationCode === CalibrationCode.AtEnd.value) {
        if (isProjectEditable(projectCollection)) {
          endDistance.prop('readonly', false).val(lastLink.addrMRange.end);
        } else {
          endDistance.val(lastLink.addrMRange.end);
        }
        formState.setEndDistanceOriginal(lastLink.addrMRange.end);
      }
    }
  };

  const updateForm = (selected) => {
    if (!selected || !selected[0]) return;
    changeDropDownValue(selected[0].status, selected);
    const projectLinkMaxByEndAddressM = _.maxBy(selected, link => link.addrMRange.end);
    if (projectLinkMaxByEndAddressM) {
      const selectedDiscontinuity = projectLinkMaxByEndAddressM.addrMRange.end === 0
        ? _.minBy(selected, pl => pl.discontinuity).discontinuity
        : projectLinkMaxByEndAddressM.discontinuity;

      $('#discontinuityDropdown').val(selectedDiscontinuity.toString());
    }
  };

  const updateFormControls = (changeType, selectedLinks, projectCollection, options = {}) => {
    const { markDirty = true } = options;
    const rootElement = $(menuSelector);

    const formControls = {
      tie: $('#tie'),
      osa: $('#osa'),
      trackCode: $('#trackCodeDropdown'),
      discontinuity: $('#discontinuityDropdown'),
      adminClass: $('#administrativeClassDropdown')
    };

    const uiElements = {
      devTool: rootElement.find('.dev-address-tool'),
      newRoadAddress: rootElement.find('.new-road-address'),
      changeDirection: rootElement.find('.changeDirectionDiv'),
      distanceValue: rootElement.find('#distanceValue'),
      updateButton: rootElement.find('.link-form button.update')
    };

    const enableFields = (enabled) => {
      Object.values(formControls).forEach(control => control.prop('disabled', !enabled));
    };

    const mapLinkData = (link, status) => ({
      id: link.id,
      linkId: link.linkId,
      status: status,
      roadLinkSource: link.roadLinkSource,
      points: link.points,
      linearLocationId: link.linearLocationId
    });

    const setDirtyLinks = (status) => {
      if (projectCollection && markDirty) {
        projectCollection.setDirty(selectedLinks.map(link => mapLinkData(link, status)));
      }
    };

    const syncTmpDirty = () => {
      if (projectCollection && markDirty) {
        projectCollection.setTmpDirty(projectCollection.getDirty());
      }
    };

    switch (changeType) {
      case RoadAddressChangeType.Terminated.description:
        enableFields(false);
        uiElements.devTool.prop('hidden', false);
        uiElements.newRoadAddress.prop('hidden', true);
        uiElements.changeDirection.prop('hidden', true);
        uiElements.distanceValue.prop('hidden', true);
        setDirtyLinks(RoadAddressChangeType.Terminated.value);
        break;

      case RoadAddressChangeType.New.description:
        enableFields(true);
        uiElements.devTool.prop('hidden', false);
        uiElements.newRoadAddress.prop('hidden', false);
        setDirtyLinks(RoadAddressChangeType.New.value);
        if (selectedLinks[0].id !== -1) {
          fillDistanceValues(selectedLinks, projectCollection);
          uiElements.changeDirection.prop('hidden', false);
          uiElements.distanceValue.prop('hidden', false);
        } else {
          uiElements.distanceValue.prop('hidden', false);
        }
        break;

      case RoadAddressChangeType.Unchanged.description:
        formControls.tie.prop('disabled', true);
        formControls.osa.prop('disabled', true);
        formControls.trackCode.prop('disabled', true);
        formControls.discontinuity.prop('disabled', false);
        formControls.adminClass.prop('disabled', false);

        uiElements.devTool.prop('hidden', false);
        uiElements.newRoadAddress.prop('hidden', false);
        uiElements.changeDirection.prop('hidden', true);
        uiElements.distanceValue.prop('hidden', true);

        setDirtyLinks(RoadAddressChangeType.Unchanged.value);
        break;

      case RoadAddressChangeType.Transfer.description:
        enableFields(true);
        uiElements.newRoadAddress.prop('hidden', false);
        uiElements.devTool.prop('hidden', false);
        uiElements.distanceValue.prop('hidden', true);
        setDirtyLinks(RoadAddressChangeType.Transfer.value);
        break;

      case RoadAddressChangeType.Numbering.description: {
        uiElements.devTool.prop('hidden', false);
        new ConfirmPopup('Numerointi koskee kokonaista tieosaa. Valintaasi on tarvittaessa laajennettu koko tieosale.', { type: 'alert' });

        const isHandled = selectedLinks[0] && selectedLinks[0].status !== RoadAddressChangeType.NotHandled.value;

        formControls.tie.prop('disabled', isHandled);
        formControls.osa.prop('disabled', isHandled);
        formControls.trackCode.prop('disabled', true);
        formControls.discontinuity.prop('disabled', false);
        formControls.adminClass.prop('disabled', true);
        uiElements.distanceValue.prop('hidden', true);

        setDirtyLinks(RoadAddressChangeType.Numbering.value);
        uiElements.newRoadAddress.prop('hidden', false);
        uiElements.updateButton.prop('disabled', false);

        break;
      }

      case RoadAddressChangeType.Revert.description:
        uiElements.devTool.prop('hidden', true);
        uiElements.newRoadAddress.prop('hidden', true);
        uiElements.changeDirection.prop('hidden', true);
        uiElements.distanceValue.prop('hidden', true);
        uiElements.updateButton.prop('disabled', false);
        break;

      default:
        uiElements.devTool.prop('hidden', true);
        uiElements.newRoadAddress.prop('hidden', true);
        uiElements.changeDirection.prop('hidden', true);
        uiElements.distanceValue.prop('hidden', true);
        break;
    }

    syncTmpDirty();
  };

  const validateEVK = (evkValue, changeType) => {
    if (changeType.value === RoadAddressChangeType.Terminated.value) {
      return true;
    }
    let isValidEvk = _.some(validEvks, evk => evk.value === evkValue);
    if (evkValue === 0 && changeType !== RoadAddressChangeType.Revert) {
      isValidEvk = false;
    }
    return isValidEvk;
  };

  return {
    defineOptionModifiers,
    checkInputs,
    updateForm,
    updateFormControls,
    validateEVK
  };
}
