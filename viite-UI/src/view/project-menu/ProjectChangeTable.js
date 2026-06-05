// Displays changes made to the project once "avaa projektin yhteenvetotaulukko" button is clicked. It supports sorting and is used for entering project edit/creation menu
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { eventbus } from '@utils/Eventbus.js';
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';

export function ProjectChangeTable(projectChangeInfoModel, projectCollection) {

    let changeTableOpen = false;
    let currentValidations = {};
    const RoadAddressChangeType = ViiteEnumerations.RoadAddressChangeType;
    const ProjectStatus = ViiteEnumerations.ProjectStatus;
    let windowMaximized = false;
    let initialHeightAutoSized = false;
    let callbacks = {
      onClosed: null,
      onValidationResult: null
    };

    const isChangeTableOpen = function () {
      return changeTableOpen;
    };

    const changeTable = $(`
      <div class="change-table-frame">

            <div class="change-table-header">Validointi ok. Alla näet muutokset projektissa.</div>
            <button class="close wbtn-close">Sulje <i class="fas fa-window-close"></i></button>
            <button class="max wbtn-max"><span id="buttonText">Suurenna </span><span id="sizeSymbol" class="size-symbol">□</span></button>
            <div class="resize-handle-vertical"></div>


        <div class="change-table-dimension-headers">
            <table class="change-table-dimensions">
                <thead>
                  <tr class="change-table-top-header">
                    <th id="label-type" colspan="2">Ilmoitus</th>
                    <th id="label-source" colspan="10">Nykyosoite<i id="label-source-btn" class="btn-icon sort fas fa-sort"></i></th>
                    <th colspan="1"></th>
                    <th id="label-target" colspan="10">Uusi osoite<i id="label-target-btn" class="btn-icon sort fas fa-sort"></i></th>
                  </tr>
                    <tr class="change-header">
                        <th class="project-change-table-dimension-header" colspan="2"></th>
                        
                        <th class="project-change-table-dimension-header">TIE</th>
                        <th class="project-change-table-dimension-header">AJR</th>
                        <th class="project-change-table-dimension-header">OSA</th>
                        <th class="project-change-table-dimension-header">AET</th>
                        <th class="project-change-table-dimension-header">LET</th>
                        <th class="project-change-table-dimension-header">PITUUS</th>
                        <th class="project-change-table-dimension-header">JATK</th>
                        <th class="project-change-table-dimension-header">HALL</th>
                        <th class="project-change-table-dimension-header elinvoimakeskus" colspan="2">ELINVOIMAKESKUS</th>
                        
                        <th class="project-change-table-dimension-header target">KÄÄNTÖ</th>
                        
                        <th class="project-change-table-dimension-header">TIE</th>
                        <th class="project-change-table-dimension-header">AJR</th>
                        <th class="project-change-table-dimension-header">OSA</th>
                        <th class="project-change-table-dimension-header">AET</th>
                        <th class="project-change-table-dimension-header">LET</th>
                        <th class="project-change-table-dimension-header">PITUUS</th>
                        <th class="project-change-table-dimension-header">JATK</th>
                        <th class="project-change-table-dimension-header">HALL</th>
                        <th class="project-change-table-dimension-header elinvoimakeskus" colspan="2">ELINVOIMAKESKUS</th>
                    </tr>
                </thead>
                <tbody>
                    </tbody>
            </table>
        </div>
      </div>
    `);

    function show() {
      $('.container').first().append(changeTable);
      initialHeightAutoSized = false;
      
      const tableWidthPercent = 60;
      const tableHeight = 280;
      
      // Calculate pixel values for positioning
      const windowWidth = $(window).width();
      const tableWidthPx = (windowWidth * tableWidthPercent) / 100;
      
      // Center horizontally: (Window - TableWidth) / 2
      const leftPos = (windowWidth - tableWidthPx) / 2;
      
      // Center vertically
      const topPos = ($(window).height() - tableHeight) / 2.2;

      changeTable.css({
        'top': topPos + 'px',
        'left': leftPos + 'px',
        'width': tableWidthPercent + '%',
        'height': tableHeight + 'px',
        'position': 'fixed' 
      });

      resetInteractions();
      interact(changeTable[0]).unset();
      bindEvents();
      getChanges();
      enableTableInteractions();
    }

    function autoSizeInitialHeight(rowCount) {
      if (initialHeightAutoSized || windowMaximized) {
        return;
      }

      const $scrollArea = changeTable.find('.change-table-dimension-headers');

      if (!changeTable.length || !$scrollArea.length) {
        return;
      }

      const maxVisibleRows = 10;
      const visibleRows = Math.max(1, Math.min(maxVisibleRows, rowCount || 0));
      const firstRowHeight = $scrollArea.find('tbody tr:first').outerHeight(true) || 28;
      const headerHeight = _.reduce($scrollArea.find('thead tr').toArray(), function (sum, row) {
        return sum + ($(row).outerHeight(true) || 0);
      }, 0);

      const frameChromeHeight = (changeTable.outerHeight() || 0) - ($scrollArea.height() || 0);
      const desiredScrollAreaHeight = headerHeight + (firstRowHeight * visibleRows);
      const minHeight = 280;
      const maxHeight = Math.floor($(window).height() * 0.9);
      const desiredFrameHeight = Math.max(minHeight, Math.min(maxHeight, frameChromeHeight + desiredScrollAreaHeight));
      const centeredTop = Math.max(10, ($(window).height() - desiredFrameHeight) / 2.2);

      changeTable.css({
        height: desiredFrameHeight + 'px',
        top: centeredTop + 'px'
      });

      initialHeightAutoSized = true;
    }

    function hide() {
      eventbus.trigger('projectChangeTable:closed');
      changeTableOpen = false;
      if (typeof callbacks.onClosed === 'function') {
        callbacks.onClosed();
      }
      resetInteractions();
      interact(changeTable[0]).unset();
      changeTable.remove();
    }

    function resetInteractions() {
      if (changeTable && changeTable.length > 0) {
        changeTable[0].setAttribute('data-x', 0);
        changeTable[0].setAttribute('data-y', 0);
        changeTable.css('transform', 'none');
      }
    }

    function getChangeType(changeTypeValue) {
      const changeType = _.find(ViiteEnumerations.ChangeType, function (obj) {
        return obj.value === changeTypeValue;
      });
      return changeType.displayText;
    }

    function getChanges() {
      const currentProject = projectCollection.getCurrentProject();
      projectChangeInfoModel.getChanges(
        currentProject.project.id,
        function () {
          const source = changeTable.find('[id=label-source-btn]');
          const target = changeTable.find('[id=label-target-btn]');
          if (source.hasClass('fa-sort-down') || source.hasClass('fa-sort-up')) {
            projectChangeInfoModel.sortChanges('source', source.attr('class').match('fa-sort-up'));
          } else if (target.hasClass('fa-sort-down') || target.hasClass('fa-sort-up')) {
            projectChangeInfoModel.sortChanges('target', target.attr('class').match('fa-sort-up'));
          }
        },
        showChangeTable
      );
    }

    // Most validation logic is in the backend, but we have some redundancy here to highlight errors that might have slipped past initial validation
    function validateLengthValues(changeTableData) {
      if (!changeTableData || !changeTableData.changeInfoSeq) {
        return { isValid: true, hasNegativeLength: false, hasLengthMismatch: false };
      }

      let allValid = true;
      let hasNegativeLengthTotal = false;
      let hasLengthMismatchTotal = false;

      const validationResults = changeTableData.changeInfoSeq.map(function(change) {
        let sourceLength = 0;
        let targetLength = 0;
        let changeHasNegativeLength = false;
        let changeHasMismatch = false;

        try {
          const isNew = change.changetype === RoadAddressChangeType.New.value;
          const isTerminated = change.changetype === RoadAddressChangeType.Terminated.value;

          if (!isNew && change.source && change.source.addrMRange) {
            sourceLength = change.source.addrMRange.end - change.source.addrMRange.start;
          }

          if (!isTerminated && change.target && change.target.addrMRange) {
            targetLength = change.target.addrMRange.end - change.target.addrMRange.start;
          }

          changeHasNegativeLength = (!isNew && sourceLength < 0) || (!isTerminated && targetLength < 0);
          changeHasMismatch = (!isNew && !isTerminated) && (sourceLength !== targetLength);

          if (changeHasNegativeLength) hasNegativeLengthTotal = true;
          if (changeHasMismatch) hasLengthMismatchTotal = true;

        } catch (e) {
          console.warn("Validation skipped", e);
        }

        const isValid = !changeHasNegativeLength && !changeHasMismatch;
        if (!isValid) allValid = false;

        return {
          isValid: isValid,
          hasNegativeLength: changeHasNegativeLength,
          hasLengthMismatch: changeHasMismatch,
          sourceLength: sourceLength,
          targetLength: targetLength,
          change: change
        };
      });

      return {
        isValid: allValid,
        hasNegativeLength: hasNegativeLengthTotal,
        hasLengthMismatch: hasLengthMismatchTotal,
        results: validationResults
      };
    }

    function hasChangeTableData(projectChangeData) {
      return !_.isUndefined(projectChangeData) &&
        !_.isUndefined(projectChangeData.changeTable) &&
        projectChangeData.changeTable !== null;
    }

    function applyValidationHeader($changeTableHeader, hasNegativeLength, hasLengthMismatch) {
      if (hasNegativeLength) {
        $changeTableHeader.html($(`<div class="warning-message">Pituuksissa on negatiivisia arvoja. Tarkista muutokset tai ota yhteyttä Viite tukeen.</div>`));
      } else if (hasLengthMismatch) {
        $changeTableHeader.html($(`<div class="warning-message">Nykyosoitteen ja uuden osoitteen pituudet eivät täsmää. Ota yhteyttä Viite tukeen.</div>`));
      }
    }

    function cacheRowValidations(validation) {
      currentValidations = {};
      if (validation.results) {
        validation.results.forEach((result, index) => {
          if (!result.isValid && result.change) {
            currentValidations[result.change.id || index] = result;
          }
        });
      }
    }

    function buildChangeRows(changeInfoSeqList) {
      let htmlTable = '';

      _.each(changeInfoSeqList, function (changeInfoSeq, index) {
        const rowColorClass = (index % 2 !== 1) ? 'white-row' : 'gray-row';
        const rowValidation = currentValidations[changeInfoSeq.id || index];
        const hasLengthError = rowValidation && !rowValidation.isValid;
        const rowClass = `${rowColorClass}${hasLengthError ? ' invalid-row' : ''}`;
        const rowId = changeInfoSeq.id || index;

        htmlTable += `<tr class="row-changes ${rowClass}" data-row-id="${rowId}">`;

        if (changeInfoSeq.changetype === RoadAddressChangeType.New.value) {
          htmlTable += getEmptySource(changeInfoSeq);
        } else {
          htmlTable += getSourceInfo(changeInfoSeq, rowId);
        }

        htmlTable += getReversed(changeInfoSeq);

        if (changeInfoSeq.changetype === RoadAddressChangeType.Terminated.value) {
          htmlTable += getEmptyTarget();
        } else {
          htmlTable += getTargetInfo(changeInfoSeq, rowId);
        }

        htmlTable += `</tr>`;
      });

      return htmlTable;
    }

    function updateHeaderAndValidationState(projectChangeData, hasLengthMismatch, hasNegativeLength, $changeTableHeader) {
      const projectDate = new Date(projectChangeData.changeTable.changeDate).toLocaleDateString('fi-FI');

      if (!hasLengthMismatch && !hasNegativeLength) {
        $changeTableHeader.html($(`
          <div>Validointi ok. Alla näet muutokset projektissa.</div>
          <div>Alkupäivämäärä: ${projectDate}</div>
        `));
      }

      const currentProject = projectCollection.getCurrentProject();
      const shouldClearValidations = changeTable.css('display') === "block" &&
        currentProject.project.statusCode === ProjectStatus.Incomplete.value &&
        !hasLengthMismatch &&
        !hasNegativeLength;

      if (shouldClearValidations) {
        currentValidations = {};
      }

      if (typeof callbacks.onValidationResult === 'function') {
        callbacks.onValidationResult({
          hasErrors: hasLengthMismatch || hasNegativeLength,
          publishable: !hasLengthMismatch && !hasNegativeLength
        });
      }
    }

    function showChangeTable(projectChangeData) {
      let htmlTable = "";
      const warningM = projectChangeData?.warningMessage;
      const $changeTableHeader = changeTable.find('.change-table-header');
      const hasData = hasChangeTableData(projectChangeData);

      if (!_.isUndefined(warningM))
        new ConfirmPopup(warningM, { type: "alert" });

      if (hasData) {
        const validation = validateLengthValues(projectChangeData.changeTable);

        applyValidationHeader($changeTableHeader, validation.hasNegativeLength, validation.hasLengthMismatch);
        cacheRowValidations(validation);
        htmlTable = buildChangeRows(projectChangeData.changeTable.changeInfoSeq);

        changeTable.find('.row-changes').remove();
        changeTable.find('.change-table-dimensions tbody').append($(htmlTable));
        autoSizeInitialHeight(projectChangeData.changeTable.changeInfoSeq.length);

        changeTableOpen = true;
        updateHeaderAndValidationState(projectChangeData, validation.hasLengthMismatch, validation.hasNegativeLength, $changeTableHeader);
      } else {
        changeTable.find('.row-changes').remove();
        changeTable.find('.change-table-dimensions tbody').append($(htmlTable));
        autoSizeInitialHeight(0);

        changeTableOpen = true;
        $changeTableHeader.html($(`<div class="warning-message">Tarkista validointitulokset. Yhteenvetotaulukko voi olla puutteellinen.</div>`));
      }
    }

    function setCallbacks(newCallbacks) {
      callbacks = Object.assign({}, callbacks, newCallbacks || {});
    }

    function bindEvents() {
      changeTable.find('.row-changes').remove();

      const MINIMIZED_HEIGHT = '260px';
      const MAXIMIZED_HEIGHT = '800px';
      const SHARED_WIDTH    = '1135px';
      const MINIMIZED_TOP   = '620px';
      const MAXIMIZED_TOP   = '50px';

      changeTable.on('click', 'button.max', function () {
        resetInteractions();
        
        if (windowMaximized) {
          changeTable.height(MINIMIZED_HEIGHT);
          changeTable.width(SHARED_WIDTH);
          changeTable.css('top', MINIMIZED_TOP);
          changeTable.find('#buttonText').text("Suurenna ");
          changeTable.find('#sizeSymbol').text("□");
          windowMaximized = false;
        } else {
          changeTable.height(MAXIMIZED_HEIGHT);
          changeTable.width(SHARED_WIDTH);
          changeTable.css('top', MAXIMIZED_TOP);
          changeTable.find('#buttonText').text("Pienennä ");
          changeTable.find('#sizeSymbol').text("_");
          windowMaximized = true;
        }
      });

      changeTable.on('click', 'button.close', function () {
        hide();
      });

      changeTable.on('click', "i[id^='label-'][id$='-btn']", function (event) {
        sortChanges(event.target);
      });
    }

    function sortChanges(btn) {
      const $btn = $(btn);
      if ($btn.hasClass('fa-sort-up') || $btn.hasClass('fa-sort')) {
        $btn.removeClass('fa-sort');
        $btn.removeClass('fa-sort-up');
        $btn.addClass('fa-sort-down');
      } else {
        $btn.removeClass('fa-sort-down');
        $btn.addClass('fa-sort-up');
      }

      const side = btn.id.match('-(.*)-')[1];
      const otherBtn = changeTable.find(`[id=label-${side === 'source' ? 'target' : 'source'}-btn`);
      otherBtn.removeClass('fa-sort-down');
      otherBtn.removeClass('fa-sort-up');
      otherBtn.addClass('fa-sort');

      const projectChanges = projectChangeInfoModel.sortChanges(side, btn.className.match('fa-sort-up'));
      showChangeTable(projectChanges);
    }

    function getReversed(changeInfoSeq) {
      return changeInfoSeq.reversed 
        ? `<td class="project-change-table-dimension">&#10004;</td>` 
        : `<td class="project-change-table-dimension"></td>`;
    }

    function getAdministrativeClassText(administrativeClass) {
      switch (administrativeClass) {
        case 1: return "Valtio";
        case 2: return "Kunta";
        case 3: return "Yksit.";
        default: return "Yksit.";
      }
    }

    function emptyCells(count) {
      return Array.from({ length: count }, () => `<td class="project-change-table-dimension"></td>`).join('');
    }

    function getEmptySource(changeInfoSeq) {
      return `<td class="project-change-table-dimension-first" colspan="2">
        ${getChangeType(changeInfoSeq.changetype)}
      </td>
      ${emptyCells(8)}
      <td class="project-change-table-dimension elinvoimakeskus" colspan="2"></td>`;
    }

    function getEmptyTarget() {
      return `${emptyCells(8)}
      <td class="project-change-table-dimension elinvoimakeskus" colspan="2"></td>`;
    }

    function getTargetInfo(changeInfoSeq, rowId) {
      const targetLength = changeInfoSeq.target.addrMRange.end - changeInfoSeq.target.addrMRange.start;
      const rowValidation = rowId && currentValidations && currentValidations[rowId];
      const isLengthInvalid = rowValidation && !rowValidation.isValid;

      const formatLength = (value) => {
        const lengthClass = isLengthInvalid ? 'invalid-value' : '';
        return `<span class="${lengthClass}">${value}</span>`;
      };

      return `
        <td class="project-change-table-dimension">${changeInfoSeq.target.roadNumber}</td>
        <td class="project-change-table-dimension">${changeInfoSeq.target.trackCode}</td>
        <td class="project-change-table-dimension">${changeInfoSeq.target.startRoadPartNumber}</td>
        <td class="project-change-table-dimension">${changeInfoSeq.target.addrMRange.start}</td>
        <td class="project-change-table-dimension">${changeInfoSeq.target.addrMRange.end}</td>
        <td class="project-change-table-dimension">${formatLength(targetLength, false)}</td>
        <td class="project-change-table-dimension">${changeInfoSeq.target.discontinuity}</td>
        <td class="project-change-table-dimension">${getAdministrativeClassText(changeInfoSeq.target.administrativeClass)}</td>
        <td class="project-change-table-dimension elinvoimakeskus" colspan="2">${changeInfoSeq.target.elinvoimakeskus || ''}</td>
      `;
    }

    function getSourceInfo(changeInfoSeq, rowId) {
      const sourceLength = changeInfoSeq.source.addrMRange.end - changeInfoSeq.source.addrMRange.start;
      const rowValidation = rowId && currentValidations && currentValidations[rowId];
      const isLengthInvalid = rowValidation && !rowValidation.isValid;

      const formatLength = (value) => {
        const lengthClass = isLengthInvalid ? 'invalid-value' : '';
        return `<span class="${lengthClass}">${value}</span>`;
      };

      return `
        <td class="project-change-table-dimension-first" colspan="2">${getChangeType(changeInfoSeq.changetype)}</td>
        <td class="project-change-table-dimension">${changeInfoSeq.source.roadNumber}</td>
        <td class="project-change-table-dimension">${changeInfoSeq.source.trackCode}</td>
        <td class="project-change-table-dimension">${changeInfoSeq.source.startRoadPartNumber}</td>
        <td class="project-change-table-dimension">${changeInfoSeq.source.addrMRange.start}</td>
        <td class="project-change-table-dimension">${changeInfoSeq.source.addrMRange.end}</td>
        <td class="project-change-table-dimension">${formatLength(sourceLength, true)}</td>
        <td class="project-change-table-dimension">${changeInfoSeq.source.discontinuity}</td>
        <td class="project-change-table-dimension">${getAdministrativeClassText(changeInfoSeq.source.administrativeClass)}</td>
        <td class="project-change-table-dimension elinvoimakeskus" colspan="2">${changeInfoSeq.source.elinvoimakeskus || ''}</td>
      `;
    }

    function dragListener(event) {
      const target = event.target;
      const x = (parseFloat(target.getAttribute('data-x')) || 0) + event.dx;
      const y = (parseFloat(target.getAttribute('data-y')) || 0) + event.dy;
      target.style.transform = `translate(${x}px, ${y}px)`;
      target.style.webkitTransform = target.style.transform;
      target.setAttribute('data-x', x);
      target.setAttribute('data-y', y);
    }

    function enableTableInteractions() {
      interact(changeTable[0]).draggable({
        allowFrom: '.change-table-header',
        onmove: dragListener,
        restrict: {
          restriction: '.container',
          elementRect: { top: 0, left: 0, bottom: 1, right: 1 }
        }
      }).resizable({
        edges: { left: true, right: true, bottom: true, top: true },
        restrictEdges: {
          outer: '.container',
          endOnly: true
        },
        restrictSize: {
          min: { width: 650, height: 158 }
        },
        inertia: true
      }).on('resizemove', function (event) {
        const target = event.target;
        let x = (parseFloat(target.getAttribute('data-x')) || 0);
        let y = (parseFloat(target.getAttribute('data-y')) || 0);
        target.style.width = `${event.rect.width}px`;
        target.style.height = `${event.rect.height}px`;
        x += event.deltaRect.left;
        y += event.deltaRect.top;
        target.style.transform = `translate(${x}px, ${y}px)`;
        target.style.webkitTransform = target.style.transform;
        target.setAttribute('data-x', x);
        target.setAttribute('data-y', y);

      });
    }

    eventbus.on('projectChangeTable:refresh', function () {
      getChanges();
      enableTableInteractions();
    });

    eventbus.on('projectChangeTable:hide', function () {
      hide();
    });

    return {
      show: show,
      hide: hide,
      bindEvents: bindEvents,
      isChangeTableOpen: isChangeTableOpen,
      setCallbacks: setCallbacks
    };
}
