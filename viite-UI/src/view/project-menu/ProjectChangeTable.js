// Displays changes made to the project once "avaa projektin yhteenvetotaulukko" button is clicked. It supports sorting and is used for entering project edit/creation menu
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { eventbus } from '@utils/eventbus.js';
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';

export function ProjectChangeTable(projectChangeInfoModel, projectCollection) {

    let changeTableOpen = false;
    let currentValidations = {};
    const RoadAddressChangeType = ViiteEnumerations.RoadAddressChangeType;
    const ProjectStatus = ViiteEnumerations.ProjectStatus;
    let windowMaximized = false;
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
                    <th id="label-type" colspan="1">Ilmoitus</th>
                    <th id="label-source" colspan="9">Nykyosoite<i id="label-source-btn" class="btn-icon sort fas fa-sort"></i></th>
                    <th colspan="1"></th>
                    <th id="label-target" colspan="9">Uusi osoite<i id="label-target-btn" class="btn-icon sort fas fa-sort"></i></th>
                  </tr>
                    <tr class="change-header">
                        <th class="project-change-table-dimension-header"></th>
                        
                        <th class="project-change-table-dimension-header">TIE</th>
                        <th class="project-change-table-dimension-header">AJR</th>
                        <th class="project-change-table-dimension-header">OSA</th>
                        <th class="project-change-table-dimension-header">AET</th>
                        <th class="project-change-table-dimension-header">LET</th>
                        <th class="project-change-table-dimension-header">PITUUS</th>
                        <th class="project-change-table-dimension-header">JATK</th>
                        <th class="project-change-table-dimension-header">HALL</th>
                        <th class="project-change-table-dimension-header elinvoimakeskus">ELINVOIMAKESKUS</th>
                        
                        <th class="project-change-table-dimension-header target">KÄÄNTÖ</th>
                        
                        <th class="project-change-table-dimension-header">TIE</th>
                        <th class="project-change-table-dimension-header">AJR</th>
                        <th class="project-change-table-dimension-header">OSA</th>
                        <th class="project-change-table-dimension-header">AET</th>
                        <th class="project-change-table-dimension-header">LET</th>
                        <th class="project-change-table-dimension-header">PITUUS</th>
                        <th class="project-change-table-dimension-header">JATK</th>
                        <th class="project-change-table-dimension-header">HALL</th>
                        <th class="project-change-table-dimension-header elinvoimakeskus">ELINVOIMAKESKUS</th>
                    </tr>
                </thead>
                <tbody>
                    </tbody>
            </table>
        </div>
      </div>
    `);

    function show() {
      const $container = $('.container');
      $container.append(changeTable);
      
      const $changeTableFrame = $('.change-table-frame');
      
      const tableWidthPercent = 60;
      const tableHeight = 280;
      
      // Calculate pixel values for positioning
      const windowWidth = $(window).width();
      const tableWidthPx = (windowWidth * tableWidthPercent) / 100;
      
      // Center horizontally: (Window - TableWidth) / 2
      const leftPos = (windowWidth - tableWidthPx) / 2;
      
      // Center vertically
      const topPos = ($(window).height() - tableHeight) / 2.2;

      $changeTableFrame.css({
        'top': topPos + 'px',
        'left': leftPos + 'px',
        'width': tableWidthPercent + '%',
        'height': tableHeight + 'px',
        'position': 'fixed' 
      });

      resetInteractions();
      interact($changeTableFrame).unset();
      bindEvents();
      getChanges();
      enableTableInteractions();
    }

    function hide() {
      const $changeTableFrame = $('.change-table-frame');
      $('#information-content').empty();
      eventbus.trigger('projectChangeTable:closed');
      changeTableOpen = false;
      if (typeof callbacks.onClosed === 'function') {
        callbacks.onClosed();
      }
      resetInteractions();
      interact($changeTableFrame).unset();
      $changeTableFrame.remove();
    }

    function resetInteractions() {
      const dragTable = $('.change-table-frame');
      if (dragTable && dragTable.length > 0) {
        dragTable[0].setAttribute('data-x', 0);
        dragTable[0].setAttribute('data-y', 0);
        dragTable.css('transform', 'none');
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
      projectChangeInfoModel.getChanges(currentProject.project.id, function () {
        const source = $('[id=label-source-btn]');
        const target = $('[id=label-target-btn]');
        if (source.hasClass('fa-sort-down') || source.hasClass('fa-sort-up')) {
          projectChangeInfoModel.sortChanges('source', source.attr('class').match('fa-sort-up'));
        } else if (target.hasClass('fa-sort-down') || target.hasClass('fa-sort-up')) {
          projectChangeInfoModel.sortChanges('target', target.attr('class').match('fa-sort-up'));
        }
      });
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

    function showChangeTable(projectChangeData) {
      let htmlTable = "";
      const warningM = projectChangeData.warningMessage;
      let hasLengthMismatch = false;
      let hasNegativeLength = false;
      const $changeTableHeader = $('.change-table-header');
      const $changeTableFrame = $('.change-table-frame');

      if (!_.isUndefined(warningM))
        new ConfirmPopup(warningM, { type: "alert" });

      if (!_.isUndefined(projectChangeData) && !_.isUndefined(projectChangeData.changeTable) && projectChangeData.changeTable !== null) {
        
        const validation = validateLengthValues(projectChangeData.changeTable);
        hasLengthMismatch = validation.hasLengthMismatch;
        hasNegativeLength = validation.hasNegativeLength;

        if (hasNegativeLength) {
          $changeTableHeader.html($(`<div class="warning-message">Pituuksissa on negatiivisia arvoja. Tarkista muutokset tai ota yhteyttä Viite tukeen.</div>`));
        } else if (hasLengthMismatch) {
          $changeTableHeader.html($(`<div class="warning-message">Nykyosoitteen ja uuden osoitteen pituudet eivät täsmää. Ota yhteyttä Viite tukeen.</div>`));
        }

        currentValidations = {};
        if (validation.results) {
          validation.results.forEach((result, index) => {
            if (!result.isValid && result.change) {
              currentValidations[result.change.id || index] = result;
            }
          });
        }

        _.each(projectChangeData.changeTable.changeInfoSeq, function (changeInfoSeq, index) {
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
      }

      const $rowChanges = $('.row-changes');
      $rowChanges.remove();
      $('.change-table-dimensions tbody').append($(htmlTable));
      
      changeTableOpen = true;
      
      if (projectChangeData && !_.isUndefined(projectChangeData.changeTable)) {
        const projectDate = new Date(projectChangeData.changeTable.changeDate).toLocaleDateString('fi-FI');

        if (!hasLengthMismatch && !hasNegativeLength) {
          $changeTableHeader.html($(`
            <div>Validointi ok. Alla näet muutokset projektissa.</div>
            <div>Alkupäivämäärä: ${projectDate}</div>
          `));
        }

        const currentProject = projectCollection.getCurrentProject();

        if ($changeTableFrame.css('display') === "block" &&
            currentProject.project.statusCode === ProjectStatus.Incomplete.value &&
            !hasLengthMismatch &&
            !hasNegativeLength) {
          currentValidations = {};
        }

        if (typeof callbacks.onValidationResult === 'function') {
          callbacks.onValidationResult({
            hasErrors: hasLengthMismatch || hasNegativeLength,
            publishable: !hasLengthMismatch && !hasNegativeLength
          });
        }
      } else {
        $changeTableHeader.html($(`<div class="warning-message">Tarkista validointitulokset. Yhteenvetotaulukko voi olla puutteellinen.</div>`));
      }
    }

    function setCallbacks(newCallbacks) {
      callbacks = Object.assign({}, callbacks, newCallbacks || {});
    }

    function bindEvents() {
      const $rowChanges = $('.row-changes');
      $rowChanges.remove();
      eventbus.on('projectChanges:fetched', function (projectChangeData) {
        showChangeTable(projectChangeData);
      });

      changeTable.on('click', 'button.max', function () {
        resetInteractions();
        const $changeTableFrame = $('.change-table-frame');
        
        if (windowMaximized) {
          $changeTableFrame.height('260px');
          $changeTableFrame.width('1135px');
          $changeTableFrame.css('top', '620px');
          $('[id=buttonText]').text("Suurenna ");
          $('[id=sizeSymbol]').text("□");
          windowMaximized = false;
        } else {
          $changeTableFrame.height('800px');
          $changeTableFrame.width('1135px');
          $changeTableFrame.css('top', '50px');
          $('[id=buttonText]').text("Pienennä ");
          $('[id=sizeSymbol]').text("_");
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
      const otherBtn = $(`[id=label-${side === 'source' ? 'target' : 'source'}-btn`);
      otherBtn.removeClass('fa-sort-down');
      otherBtn.removeClass('fa-sort-up');
      otherBtn.addClass('fa-sort');

      const projectChanges = projectChangeInfoModel.sortChanges(side, btn.className.match('fa-sort-up'));
      eventbus.trigger('projectChanges:fetched', projectChanges);
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
      return `<td class="project-change-table-dimension-first">
        ${getChangeType(changeInfoSeq.changetype)}
      </td>
      ${emptyCells(9)}`;
    }

    function getEmptyTarget() {
      return emptyCells(9);
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
        <td class="project-change-table-dimension">${changeInfoSeq.target.elinvoimakeskus || ''}</td>
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
        <td class="project-change-table-dimension-first">${getChangeType(changeInfoSeq.changetype)}</td>
        <td class="project-change-table-dimension">${changeInfoSeq.source.roadNumber}</td>
        <td class="project-change-table-dimension">${changeInfoSeq.source.trackCode}</td>
        <td class="project-change-table-dimension">${changeInfoSeq.source.startRoadPartNumber}</td>
        <td class="project-change-table-dimension">${changeInfoSeq.source.addrMRange.start}</td>
        <td class="project-change-table-dimension">${changeInfoSeq.source.addrMRange.end}</td>
        <td class="project-change-table-dimension">${formatLength(sourceLength, true)}</td>
        <td class="project-change-table-dimension">${changeInfoSeq.source.discontinuity}</td>
        <td class="project-change-table-dimension">${getAdministrativeClassText(changeInfoSeq.source.administrativeClass)}</td>
        <td class="project-change-table-dimension">${changeInfoSeq.source.elinvoimakeskus || ''}</td>
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
      interact('.change-table-frame').draggable({
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
