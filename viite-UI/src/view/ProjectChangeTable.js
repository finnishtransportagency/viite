(function (root) {
  root.ProjectChangeTable = function (projectChangeInfoModel, projectCollection) {
    let changeTableOpen = false;
    let windowMaximized = localStorage.getItem('changeTableWindowMaximized') === 'true';

    const formCommon = new FormCommon('');
    const RoadAddressChangeType = ViiteEnumerations.RoadAddressChangeType;
    const ProjectStatus = ViiteEnumerations.ProjectStatus;
    const isChangeTableOpen = () => changeTableOpen;

    const changeTable = $(`
      <div class="change-table-frame">
          <div class="change-table-header font-resize">Validointi OK.</div>
          <div class="change-table-dimension-headers">
            <table class="change-table-dimensions">
              <thead>
                <tr class="project-change-table-dimension-header">
                  <th class="project-change-table-dimension-first-h">Ilmoitus</th>
                  <th colspan="9" id="label-source-header">
                    Nykyosoite <i id="label-source-btn" class="btn-icon fas fa-sort"></i>
                  </th>
                  <th class="project-change-table-dimension-h dimension-reversed"></th>
                  <th colspan="9" id="label-target-header">
                    Uusi osoite <i id="label-target-btn" class="btn-icon fas fa-sort"></i>
                  </th>
                </tr>
                <tr class="project-change-table-dimension-header">
                  <th class="project-change-table-dimension-h"></th>
                  <th class="project-change-table-dimension-h wide-column">TIE</th>
                  <th class="project-change-table-dimension-h">AJR</th>
                  <th class="project-change-table-dimension-h">OSA</th>
                  <th class="project-change-table-dimension-h">AET</th>
                  <th class="project-change-table-dimension-h">LET</th>
                  <th class="project-change-table-dimension-h">PIT</th>
                  <th class="project-change-table-dimension-h">JATK</th>
                  <th class="project-change-table-dimension-h wide-column">HALL</th>
                  <th class="project-change-table-dimension-h">ELY</th>
                  <th class="project-change-table-dimension-h dimension-reversed">KÄÄNNETTY</th>
                  <th class="project-change-table-dimension-h wide-column">TIE</th>
                  <th class="project-change-table-dimension-h">AJR</th>
                  <th class="project-change-table-dimension-h">OSA</th>
                  <th class="project-change-table-dimension-h">AET</th>
                  <th class="project-change-table-dimension-h">LET</th>
                  <th class="project-change-table-dimension-h">PIT</th>
                  <th class="project-change-table-dimension-h">JATK</th>
                  <th class="project-change-table-dimension-h wide-column">HALL</th>
                  <th class="project-change-table-dimension-h">ELY</th>
                </tr>
              </thead>
              <tbody></tbody>
            </table>
          </div>
      </div>
    `);

    function show() {
      $('.container').append(changeTable);
      resetInteractions();
      interact('.change-table-frame').unset();
      bindEvents();
      getChanges();
      enableTableInteractions();

      // Dynamically calculate initial height based on content
      const maxViewportHeight = $(window).height();
      const headerHeight = $('.change-table-header').outerHeight(true);
      const contentHeight = $('.change-table-dimensions').outerHeight(true);
      const totalContentHeight = headerHeight + contentHeight + 30; // Add a little buffer

      const desiredHeight = Math.min(totalContentHeight, maxViewportHeight * 0.9); // Fill up to 90% of screen

      changeTable.css({
        transform: 'none',
        height: `${desiredHeight}px`,
        maxHeight: `${maxViewportHeight * 0.95}px`, // Prevent it from exceeding the viewport
        overflowY: 'auto' // Allow scrolling if necessary
      });

      resizeTable(windowMaximized);
    }


    function hide() {
      changeTableOpen = false;
      formCommon.enableFormInteractions();
      $('#information-content').empty();
      $('#send-button').prop('disabled', true).attr('title', 'Hyväksy yhteenvedon jälkeen');
      $('#recalculate-button').attr('title', 'Etäisyyslukemat on päivitetty');
      $('#changes-button').prop('disabled', false).removeAttr('title');
      resetInteractions();
      interact('.change-table-frame').unset();
      $('.change-table-frame').remove();
    }

    function centerTableInViewport() {
      const $table = $('.change-table-frame');
      const windowWidth = $(window).width();
      const windowHeight = $(window).height();
      const tableWidth = $table.outerWidth();
      const tableHeight = $table.outerHeight();
      const left = Math.max((windowWidth - tableWidth) / 3, 0);
      const top = Math.max((windowHeight - tableHeight) / 4, 0);

      $table.css({ position: 'absolute', left: `${left}px`, top: `${top}px` });
    }

    function resetInteractions() {
      const dragTable = $('.change-table-frame');
      if (dragTable.length > 0) {
        dragTable[0].setAttribute('data-x', 0);
        dragTable[0].setAttribute('data-y', 0);
        dragTable.css('transform', 'none');
      }
    }

    function getChangeType(changeTypeValue) {
      const changeType = _.find(ViiteEnumerations.ChangeType, obj => obj.value === changeTypeValue);
      return changeType.displayText;
    }

    // Get list of changes made in the project so they can be rendered on the table
    function getChanges() {
      const currentProject = projectCollection.getCurrentProject();
      projectChangeInfoModel.getChanges(currentProject.project.id, () => {
        const source = $('#label-source-btn');
        const target = $('#label-target-btn');
        if (source.hasClass('fa-sort-down') || source.hasClass('fa-sort-up')) {
          projectChangeInfoModel.sortChanges('source', source.hasClass('fa-sort-up'));
        } else if (target.hasClass('fa-sort-down') || target.hasClass('fa-sort-up')) {
          projectChangeInfoModel.sortChanges('target', target.hasClass('fa-sort-up'));
        }
      });
    }

    function showChangeTable(projectChangeData) {
      let htmlTable = '';
      const warningM = projectChangeData.warningMessage;
      if (warningM) void new ModalConfirm(warningM);

      if (projectChangeData?.changeTable) {
        _.each(projectChangeData.changeTable.changeInfoSeq, (changeInfoSeq, index) => {
          const rowColorClass = index % 2 === 0 ? 'white-row' : '';
          htmlTable += `<tr class="row-changes ${rowColorClass}">`;
          htmlTable += (changeInfoSeq.changetype === RoadAddressChangeType.New.value)
              ? getEmptySource(changeInfoSeq)
              : getSourceInfo(changeInfoSeq);
          htmlTable += getReversed(changeInfoSeq);
          htmlTable += (changeInfoSeq.changetype === RoadAddressChangeType.Terminated.value)
              ? getEmptyTarget()
              : getTargetInfo(changeInfoSeq);
          htmlTable += '</tr>';
        });
      }

      $('.row-changes').remove();
      $('.change-table-dimensions').append($(htmlTable));
      changeTableOpen = true;

      if (projectChangeData?.changeTable) {
        const projectDate = new Date(projectChangeData.changeTable.changeDate).toLocaleDateString('fi-FI');
        $('.change-table-header').html(`
          <div class="left"><p>Validointi OK</p></div>
          <div class="center"><p>Alkupäivämäärä: ${projectDate}</p></div>
          <div class="right">
            <button class="max wbtn-max" aria-label="Toggle Size" title="Suurenna taulukko"><i id="sizeIcon" class="fas fa-expand"></i></button>
            <button class="close wbtn-close" aria-label="Close"><i id="closeIcon" class="fas fa-times"></i></button>
          </div>
        `);

        const currentProject = projectCollection.getCurrentProject();
        formCommon.setDisabledAndTitleAttributesById('recalculate-button', true, 'Etäisyyslukemia ei voida päivittää yhteenvetotaulukon ollessa auki');
        formCommon.setDisabledAndTitleAttributesById('changes-button', true, 'Yhteenvetotaulukko on jo auki');

        if ($('.change-table-frame').css('display') === 'block' && currentProject.project.statusCode === ProjectStatus.Incomplete.value) {
          formCommon.setDisabledAndTitleAttributesById('send-button', false, '');
        }
      } else {
        $('.change-table-header').html(`
          <div class="left warning">Tarkista validointitulokset. Yhteenvetotaulukko voi olla puutteellinen.</div>
          <div class="right">
            <button class="max wbtn-max" aria-label="Toggle Size" title="Suurenna taulukko"><i id="sizeIcon" class="fas fa-expand"></i></button>
            <button class="close wbtn-close" aria-label="Close"><i id="closeIcon" class="fas fa-times"></i></button>
          </div>
        `);
      }

      resizeTable(windowMaximized);
      centerTableInViewport();
    }

    function bindEvents() {
      $('.row-changes').remove();

      eventbus.on('projectChanges:fetched', showChangeTable);

      changeTable.on('click', 'button.max', () => {
        resizeTable();
      });

      changeTable.on('click', 'button.close', hide);
      changeTable.on('click', '#label-source-btn, #label-target-btn', e => sortChanges(e.target));
    }

    function updateTableFontSize() {
      const tableWidth = $('.change-table-dimensions').outerWidth();
      let fontSize = '0.85rem';

      if (tableWidth > 1250) fontSize = '1.0rem';

      $('.change-table-dimensions').css('font-size', fontSize);
    }

    // Change table width based on the screen width. Uses local storage to save user preference
    function resizeTable(forceMaximized = null) {
      const $frame = $('.change-table-frame');
      const $icon = $('#sizeIcon');
      const $button = $('.wbtn-max');

      // Current state
      const currentState = windowMaximized;

      // New state
      const isMaximized = forceMaximized !== null ? forceMaximized : !currentState;
      const widthPercent = isMaximized ? 0.8 : 0.6;

      $frame.css({
        width: `${widthPercent * 100}%`,
        maxWidth: '100%',

      });

      updateTableFontSize();

      // Toggle resize icon
      if (isMaximized) {
        $icon.removeClass('fa-compress').addClass('fa-expand');
        $button.attr('title', 'Pienennä taulukko');
      } else {
        $icon.removeClass('fa-expand').addClass('fa-compress');
        $button.attr('title', 'Suurenna taulukko');
      }

      windowMaximized = isMaximized;
      localStorage.setItem('changeTableWindowMaximized', windowMaximized);

      // Calculate and set correct table height
      const maxViewportHeight = $(window).height();
      const headerHeight = $('.change-table-header').outerHeight(true);
      const tableContentHeight = $('.change-table-dimensions').outerHeight(true);
      const totalContentHeight = headerHeight + tableContentHeight;
      const desiredHeight = Math.min(totalContentHeight, maxViewportHeight * 0.9);

      changeTable.css({
        height: `${desiredHeight}px`,
        maxHeight: `${maxViewportHeight * 0.95}px`,
        overflowY: 'auto'
      });
    }

    function sortChanges(btn) {
      const $btn = $(btn);
      const idMatch = $btn.attr('id').match(/^label-(source|target)-btn$/);
      if (!idMatch) return;

      const side = idMatch[1];
      const isAscending = $btn.hasClass('fa-sort-up');

      $('.btn-icon').removeClass('fa-sort-up fa-sort-down').addClass('fa-sort');
      $btn.removeClass('fa-sort');
      $btn.addClass(isAscending ? 'fa-sort-down' : 'fa-sort-up');

      const sorted = projectChangeInfoModel.sortChanges(side, !isAscending);
      eventbus.trigger('projectChanges:fetched', sorted);
    }

    function getReversed(changeInfoSeq) {
      return `<td class="project-change-table-dimension dimension-reversed">${changeInfoSeq.reversed ? '&#10004;' : ''}</td>`;
    }

    function getAdministrativeClassText(administrativeClass) {
      switch (administrativeClass) {
        case 1: return 'Valtio';
        case 2: return 'Kunta';
        case 3: return 'Yksit.';
        default: return 'Yksit.';
      }
    }

    function getEmptySource(changeInfoSeq) {
      return `<td class="project-change-table-dimension-first">${getChangeType(changeInfoSeq.changetype)}</td>` +
          '<td class="project-change-table-dimension"></td>' + // TIE
          '<td class="project-change-table-dimension"></td>' + // AJR
          '<td class="project-change-table-dimension"></td>' + // OSA
          '<td class="project-change-table-dimension"></td>' + // AET
          '<td class="project-change-table-dimension"></td>' + // LET
          '<td class="project-change-table-dimension"></td>' + // PIT
          '<td class="project-change-table-dimension"></td>' + // JATK
          '<td class="project-change-table-dimension"></td>' + // HALL
          '<td class="project-change-table-dimension"></td>';  // ELY
    }

    function getEmptyTarget() {
      return  '<td class="project-change-table-dimension"></td>' + // TIE
          '<td class="project-change-table-dimension"></td>' + // AJR
          '<td class="project-change-table-dimension"></td>' + // OSA
          '<td class="project-change-table-dimension"></td>' + // AET
          '<td class="project-change-table-dimension"></td>' + // LET
          '<td class="project-change-table-dimension"></td>' + // PIT
          '<td class="project-change-table-dimension"></td>' + // JATK
          '<td class="project-change-table-dimension"></td>' + // HALL
          '<td class="project-change-table-dimension"></td>';  // ELY
    }


    function getTargetInfo(changeInfoSeq) {
      const t = changeInfoSeq.target;
      return `
        <td class="project-change-table-dimension">${t.roadNumber}</td>
        <td class="project-change-table-dimension">${t.trackCode}</td>
        <td class="project-change-table-dimension">${t.startRoadPartNumber}</td>
        <td class="project-change-table-dimension">${t.addrMRange.start}</td>
        <td class="project-change-table-dimension">${t.addrMRange.end}</td>
        <td class="project-change-table-dimension">${t.addrMRange.end - t.addrMRange.start}</td>
        <td class="project-change-table-dimension">${t.discontinuity}</td>
        <td class="project-change-table-dimension">${getAdministrativeClassText(t.administrativeClass)}</td>
        <td class="project-change-table-dimension">${t.ely}</td>
      `;
    }

    function getSourceInfo(changeInfoSeq) {
      const s = changeInfoSeq.source;
      return `
        <td class="project-change-table-dimension-first">${getChangeType(changeInfoSeq.changetype)}</td>
        <td class="project-change-table-dimension">${s.roadNumber}</td>
        <td class="project-change-table-dimension">${s.trackCode}</td>
        <td class="project-change-table-dimension">${s.startRoadPartNumber}</td>
        <td class="project-change-table-dimension">${s.addrMRange.start}</td>
        <td class="project-change-table-dimension">${s.addrMRange.end}</td>
        <td class="project-change-table-dimension">${s.addrMRange.end - s.addrMRange.start}</td>
        <td class="project-change-table-dimension">${s.discontinuity}</td>
        <td class="project-change-table-dimension">${getAdministrativeClassText(s.administrativeClass)}</td>
        <td class="project-change-table-dimension">${s.ely}</td>
      `;
    }

    function dragListener(event) {
      const target = event.target;
      let x = (parseFloat(target.getAttribute('data-x')) || 0) + event.dx;
      let y = (parseFloat(target.getAttribute('data-y')) || 0) + event.dy;
      target.style.transform = `translate(${x}px, ${y}px)`;
      target.setAttribute('data-x', x);
      target.setAttribute('data-y', y);
    }

    // Enable dragging and resizing
    function enableTableInteractions() {
      interact('.change-table-frame')
          .draggable({
            allowFrom: '.change-table-header',
            onmove: dragListener,
            restrict: { restriction: '.container', elementRect: { top: 0, left: 0, bottom: 1, right: 1 } }
          })
          .resizable({
            edges: { left: true, right: true, bottom: true, top: false }, // Disable top resize
            restrictEdges: { outer: '.container', endOnly: true },
            inertia: true
          })
          .on('resizemove', function (event) {
            const target = event.target;
            const currentHeight = parseFloat(target.style.height) || target.offsetHeight;
            const newHeight = currentHeight + event.deltaRect.height;

            const minHeight = 100;
            const tableContentHeight = $('.change-table-dimensions').outerHeight(true) + $('.change-table-header').outerHeight(true);

            // Restrict resizing so height can't exceed content height
            const height = Math.max(minHeight, Math.min(newHeight, tableContentHeight));

            let x = (parseFloat(target.getAttribute('data-x')) || 0) + event.deltaRect.left;
            let y = (parseFloat(target.getAttribute('data-y')) || 0) + event.deltaRect.top;

            target.style.width = `${event.rect.width}px`;
            target.style.height = `${height}px`;
            target.style.transform = `translate(${x}px, ${y}px)`;
            target.setAttribute('data-x', x);
            target.setAttribute('data-y', y);
            updateTableFontSize();
          });
    }

    eventbus.on('projectChangeTable:refresh', () => {
      getChanges();
      enableTableInteractions();
    });

    eventbus.on('projectChangeTable:hide', hide);

    return { show, hide, bindEvents, isChangeTableOpen };
  };
}(this));
