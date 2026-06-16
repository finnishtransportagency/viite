import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';

export function createProjectLinkEditorLogic(dependencies) {
  const {
    RoadAddressChangeType,
    CalibrationCode,
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

  const checkInputs = (projectChangeTable) => {
    const rootElement = $(menuSelector);
    const saveButton = rootElement.find('#saveButton');

    const selectedChangeType = $('#dropDown_0').val();
    if (selectedChangeType === RoadAddressChangeType.Revert.description) {
      saveButton.prop('disabled', projectChangeTable.isChangeTableOpen());
      return;
    }

    const tieValue = _.trim($('#tie').val() || '');
    const osaValue = _.trim($('#osa').val() || '');
    const trackValue = _.trim($('#trackCodeDropdown').val() || '');
    const roadNameValue = _.trim($('#roadName').val() || '');
    const filled = tieValue !== '' && osaValue !== '' && trackValue !== '' && trackValue !== '99' && roadNameValue !== '';

    saveButton.prop('disabled', !(filled && !projectChangeTable.isChangeTableOpen()));
  };

  const changeDropDownValue = (statusCode, selectedLinks) => {
    if (statusCode === RoadAddressChangeType.Undefined.value) {
      // Link has no road address: can only become "New"
      $('#dropDown_0').val(RoadAddressChangeType.New.description);
    } else if (statusCode !== RoadAddressChangeType.NotHandled.value) {
      // Link has already been processed: restore its action type
      const matchingType = _.find(RoadAddressChangeType, t => t.value === statusCode);
      if (matchingType) {
        $('#dropDown_0').val(matchingType.description);
      }
    }
    // NotHandled (0): reserved but unprocessed — leave at "Valitse" placeholder.

    if (selectedLinks && selectedLinks.length > 0) {
      $('#discontinuityDropdown').val(selectedLinks[selectedLinks.length - 1].discontinuity);
    }
  };

  const fillDistanceValues = (selectedLinks) => {
    const beginDistance = $('#beginDistance');
    const endDistance = $('#endDistance');

    // Always reset first so stale values from the previous link never bleed through
    beginDistance.val('--');
    endDistance.val('--');
    formState.setEndDistanceOriginal('--');

    if (selectedLinks.length === 1 && selectedLinks[0].calibrationCode === CalibrationCode.AtBoth.value) {
      beginDistance.val(selectedLinks[0].addrMRange.start);
      endDistance.val(selectedLinks[0].addrMRange.end);
      formState.setEndDistanceOriginal(selectedLinks[0].addrMRange.end);
    } else {
      const orderedByStartM = _.sortBy(selectedLinks, l => l.addrMRange.start);
      if (orderedByStartM[0].calibrationCode === CalibrationCode.AtBeginning.value) {
        beginDistance.val(orderedByStartM[0].addrMRange.start);
      }

      const lastLink = orderedByStartM[orderedByStartM.length - 1];
      if (lastLink.calibrationCode === CalibrationCode.AtEnd.value) {
        endDistance.val(lastLink.addrMRange.end);
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
      updateButton: rootElement.find('#saveButton')
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
          // Brand-new link: ensure fields show '--' (no stale values from previous selection)
          $('#beginDistance').val('--');
          $('#endDistance').val('--');
          formState.setEndDistanceOriginal('--');
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

  return {
    defineOptionModifiers,
    checkInputs,
    updateForm,
    updateFormControls
  };
}
