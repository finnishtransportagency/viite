(function (root) {
  root.ProjectList = function (projectCollection) {
    const projectStatus = ViiteEnumerations.ProjectStatus;
    let projectArray = []; // The "Source of Truth"
    let pollProjects = null;
    let eventsBound = false;

    // --- State Management ---
    const orderBy = { id: "sortStatus", reversed: false };
    const filterBox = { input: "", visible: false };

    // --- Utilities ---
    const parseFinnishDate = (dateStr) => {
      if (!dateStr) return new Date(0);
      // Converts "DD.MM.YYYY" or ISO to Date object
      const parts = dateStr.includes('.') ? dateStr.split('.') : null;
      return parts ? new Date(parts[2], parts[1] - 1, parts[0]) : new Date(dateStr);
    };

    const getIcon = (id) => {
      if (orderBy.id !== id) return 'fa-sort';
      return orderBy.reversed ? 'fa-sort-down' : 'fa-sort-up';
    };

    // --- Configuration: Headers & Sorting ---
    const headers = {
      "sortName": {
        toStr: "PROJEKTIN NIMI", width: "180",
        sortFunc: (a, b) => a.name.localeCompare(b.name, 'fi')
      },
      "sortEVK": {
        toStr: "ELINVOIMAKESKUS", width: "170",
        sortFunc: (a, b) => {
          const aEvks = a.evks || [], bEvks = b.evks || [];
          for (let i = 0; i < Math.min(aEvks.length, bEvks.length); i++) {
            if (aEvks[i] !== bEvks[i]) return aEvks[i] - bEvks[i];
          }
          return aEvks.length - bEvks.length;
        }
      },
      "sortUser": {
        toStr: "KÄYTTÄJÄ", width: "155",
        sortFunc: (a, b) => a.createdBy.localeCompare(b.createdBy, 'fi')
      },
      "sortDate": {
        toStr: "LUONTIPVM", width: "155",
        sortFunc: (a, b) => parseFinnishDate(b.createdDate) - parseFinnishDate(a.createdDate)
      },
      "sortStatus": {
        toStr: "TILA", width: "155",
        sortFunc: (a, b) => {
          const statusOrder = {
            [projectStatus.ErrorInViite.value]: 1,
            [projectStatus.InUpdateQueue.value]: 2,
            [projectStatus.UpdatingToRoadNetwork.value]: 3,
            [projectStatus.Incomplete.value]: 4,
            [projectStatus.Accepted.value]: 5,
            [projectStatus.Deleted.value]: 6,
            [projectStatus.Unknown.value]: 99
          };
          const diff = (statusOrder[a.statusCode] || 99) - (statusOrder[b.statusCode] || 99);
          return diff !== 0 ? diff : parseFinnishDate(b.createdDate) - parseFinnishDate(a.createdDate);
        }
      }
    };

    // --- Templates ---
    const staticField = (data) => `<div><label class="control-label-projects-list">${data || ''}</label></div>`;

    const renderHeader = () => {
      let html = '<thead class="project-list-header"><tr>';
      Object.keys(headers).forEach(id => {
        const h = headers[id];
        html += `<th style="width: ${h.width}px;">
          <label>${h.toStr}<i id="${id}" class="btn-icon sort fas ${getIcon(id)}"></i>`;
        if (id === "sortUser") {
          html += `<i id="filterUser" class="btn-icon fas fa-filter"></i></label>
            <span class="user-filter-input" id="userFilterSpan" style="display:none">
            <input type="text" id="userNameBox" placeholder="Käyttäjätunnus"></span>`;
        } else {
          html += `</label>`;
        }
        html += `</th>`;
      });
      html += `<th style="width: 180px;"><div class="actions"><button class="new btn-primary">Uusi tieosoiteprojekti</button></div></th></tr></thead>`;
      return html;
    };

    const renderRow = (proj) => {
      const info = proj.statusInfo || 'Ei lisätietoja';
      const dateStr = dateutil.dateObjectToFinnishString(parseFinnishDate(proj.createdDate));
      const isError = proj.statusCode === projectStatus.ErrorInViite.value;
      
      return `
        <tr class="project-list-row" data-id="${proj.id}">
          <td class="project-name-cell" style="width: ${headers.sortName.width}px;">${staticField(proj.name)}</td>
          <td class="evk-cell" style="width: ${headers.sortEVK.width}px;">${staticField(proj.evks)}</td>
          <td class="user-cell" style="width: ${headers.sortUser.width}px;">${staticField(proj.createdBy)}</td>
          <td class="date-cell" style="width: ${headers.sortDate.width}px;">${staticField(dateStr)}</td>
          <td class="status-cell" title="${info}" style="width: ${headers.sortStatus.width}px;">${staticField(proj.statusDescription)}</td>
          <td class="actions-cell" style="width: 180px;">
            <button class="project-open ${isError ? 'btn-new-error' : 'btn-primary'}" value="${proj.id}" data-status="${proj.statusCode}">
              ${isError ? 'Avaa uudelleen' : 'Avaa'}
            </button>
          </td>
        </tr>`;
    };

    const projectList = $(`
      <div class="project-table-wrapper">
        <table class="project-table">
          ${renderHeader()}
          <tbody></tbody>
          <tfoot>
            <tr><td colspan="6"><div class="content-footer">
              <label><input type="checkbox" id="OldAcceptedProjectsVisibleCheckbox"><span>Näytä kaikki tieverkolle päivitetyt projektit</span></label>
              <i id="sync" class="fas refresh-button fa-sync-alt" title="Päivitä lista"></i>
            </div></td></tr>
          </tfoot>
        </table>
      </div>`);

    let modalContainer = null;

    // --- Logic & Rendering ---
    const redrawTable = () => {
      const searchTerm = $('#userNameBox').val() ? $('#userNameBox').val().toLowerCase() : "";
      
      // Filter
      const filtered = projectArray.filter(p => 
        !searchTerm || p.createdBy.toLowerCase().includes(searchTerm)
      );

      // Sort
      const sorted = filtered.sort((a, b) => {
        const res = headers[orderBy.id].sortFunc(a, b);
        return orderBy.reversed ? -res : res;
      });

      // Render
      const $tbody = projectList.find('tbody');
      $tbody.empty();
      if (sorted.length) {
        $tbody.append(sorted.map(renderRow).join(''));
      }
    };

    const userFilterVisibility = () => {
      const $span = $('#userFilterSpan');
      const $input = $('#userNameBox');
      if (filterBox.visible) {
        $span.show();
        if (!$input.val()) $input.val(applicationModel.getSessionUsername());
      } else {
        $input.val("");
        $span.hide();
      }
      redrawTable();
    };

    // --- Actions ---
    const fetchProjects = () => {
      const onlyActive = !$('#OldAcceptedProjectsVisibleCheckbox').is(':checked');
      projectCollection.getProjects(onlyActive);
    };

    const openProjectSteps = (projectId) => {
      applicationModel.addSpinner();
      projectCollection.getProjectsWithLinksById(parseInt(projectId)).then(result => {
        hide();
        eventbus.trigger('roadAddress:openProject', result);
        if (applicationModel.isReadOnly()) $('.edit-mode-btn:visible').click();
      });
    };

    function bindEvents() {
      if (eventsBound) return;

      // Data events
      eventbus.on('roadAddressProjects:fetched', (projects) => {
        projectArray = projects.filter(p => p.statusCode !== projectStatus.Deleted.value);
        redrawTable();
        userFilterVisibility();
        $('#sync').removeClass("btn-spin");
      });

      eventbus.on('roadAddressProjectStates:fetched', (idsAndStates) => {
        projectArray.forEach(p => {
          const match = idsAndStates.find(s => s[0] === p.id);
          if (match) {
            p.statusCode = match[1];
            p.statusDescription = Object.values(projectStatus).find(e => e.value === match[1]).description;
          }
        });
        redrawTable();
        $('#sync').removeClass("btn-spin");
      });

      // UI delegation
      projectList.on('click', '.sort', function() {
        const id = this.id;
        orderBy.reversed = (orderBy.id === id) ? !orderBy.reversed : false;
        orderBy.id = id;
        
        // Update header icons manually to avoid full header redraw
        projectList.find('.sort').each(function() {
          $(this).removeClass('fa-sort fa-sort-up fa-sort-down').addClass(getIcon(this.id));
        });
        redrawTable();
      });

      projectList.on('click', '.project-open', function() {
        const $btn = $(this);
        const id = $btn.val();
        const status = parseInt($btn.data('status'));

        const proceed = () => {
          clearInterval(pollProjects);
          if (status === projectStatus.ErrorInViite.value) {
            projectCollection.reOpenProjectById(parseInt(id));
            eventbus.once("roadAddressProject:reOpenedProject", () => openProjectSteps(id));
          } else {
            openProjectSteps(id);
          }
        };

        if (status === projectStatus.InUpdateQueue.value || status === projectStatus.UpdatingToRoadNetwork.value) {
          new ConfirmPopup("Projektin muokkaaminen ei ole mahdollista, koska sitä päivitetään tieverkolle. Haluatko avata sen?", { successCallback: proceed });
        } else {
          proceed();
        }
      });

      // New project creation
      projectList.on('click', '.new', () => {
        clearInterval(pollProjects);

        const newProject = {
          id: 0,
          name: '',
          startDate: '',
          additionalInfo: '',
          createdBy: '',
          modifiedBy: '',
          dateModified: ''
        };
        
        modalContainer.close();
        applicationModel.setOpenProject(true);
        projectCollection.clearRoadAddressProjects();
        
        window.projectMenu.showProjectDetails(newProject, true, projectCollection, newProject);

        if (applicationModel.isReadOnly()) $('.edit-mode-btn:visible').click();
      });

      projectList.on('click', '#sync', function() {
        $(this).addClass("btn-spin");
        fetchProjects();
      });

      $(document).on('click', '#filterUser', () => {
        filterBox.visible = !filterBox.visible;
        userFilterVisibility();
      });

      $(document).on('keyup', '#userNameBox', redrawTable);
      $(document).on('change', '#OldAcceptedProjectsVisibleCheckbox', fetchProjects);

      eventsBound = true;
    }

    function show() {
      // Check if modal was destroyed by global modal removal
      if (modalContainer && (!modalContainer.element || !modalContainer.element.closest('body').length)) {
        modalContainer = null;
      }
      
      if (!modalContainer) {
          modalContainer = Application.getModalContainer({ 
            style: 'width: 1000px;', 
            onClose: hide
          });
        }
      modalContainer.open({
        title: 'Tieosoiteprojektit',
        content: projectList
      });
      eventbus.trigger("roadAddressProject:deactivateAllSelections");
      bindEvents();
      fetchProjects();
      pollProjects = setInterval(() => {
        projectCollection.getProjectStates(projectArray.map(p => p.id));
      }, 30000);
    }

    function hide() {
      filterBox.visible = false;
      $('#userNameBox').val('');
      $('#userFilterSpan').hide();
      eventbus.trigger("roadAddressProject:startAllInteractions");
      modalContainer.close();
      eventsBound = false;
    }

    return {
      show, hide, element: projectList,
      cleanup: () => {
        if (pollProjects) clearInterval(pollProjects);
        eventbus.off('roadAddressProjects:fetched roadAddressProjectStates:fetched');
        projectList.off();
        if (modalContainer) {
          modalContainer = null;
        }
        eventsBound = false;
      }
    };
  };
}(this));