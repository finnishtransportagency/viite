/**
 * @typedef {Object} Project
 * @property {string} name
 * @property {number} statusCode
 * @property {string} statusDescription
 * @property {string} createdBy
 * @property {string} createdDate
 * @property {string} [statusInfo]
 * @property {number} id
 */

/**
 * An array of project data models used for project list rendering.
 * @param {Project[]} projects - List of project objects to be processed.
 */

(function (root) {
  root.ProjectListModel = function (projectCollection) {
    const projectStatus = ViiteEnumerations.ProjectStatus;
    let projectArray = [];
    const headers = {
      "sortName": {
        toStr: "PROJEKTIN NIMI", width: "140",
        sortFunc: function (a, b) {
          return a.name.localeCompare(b.name, 'fi');
        }
      },
      "sortEVK": {
        toStr: "ELINVOIMAKESKUS", width: "200",
        sortFunc: function (a, b) {
          const aEvks = a.evks || [];
          const bEvks = b.evks || [];
          let i = 0;
          while (i < aEvks.length && i < bEvks.length) {
            if (aEvks[i] !== bEvks[i]) {
              return aEvks[i] - bEvks[i];
            }
            i++;
          }
          return aEvks.length - bEvks.length;
        }
      },
      "sortUser": {
        toStr: "KÄYTTÄJÄ", width: "150",
        sortFunc: function (a, b) {
          return a.createdBy.localeCompare(b.createdBy, 'fi');
        }
      },
      "sortDate": {
        toStr: "LUONTIPVM", width: "150",
        sortFunc: function (a, b) {
          const aDate = a.createdDate.split('.').reverse().join('-');
          const bDate = b.createdDate.split('.').reverse().join('-');
          return new Date(bDate) - new Date(aDate);
        }
      },
      "sortStatus": {
        toStr: "TILA", width: "150",
        sortFunc: function (a, b) {
          // By default sort projects based on this status order
          const statusOrder = {
            [projectStatus.ErrorInViite.value]: 1,
            [projectStatus.InUpdateQueue.value]: 2,
            [projectStatus.UpdatingToRoadNetwork.value]: 3,
            [projectStatus.Incomplete.value]: 4,
            [projectStatus.Accepted.value]: 5,
            [projectStatus.Deleted.value]: 6,
            [projectStatus.Unknown.value]: 99
          };
          // Get the numeric order value, defaulting to 99 if status not found
          const aOrder = statusOrder[a.statusCode] || 99;
          const bOrder = statusOrder[b.statusCode] || 99;
          if (aOrder !== bOrder) {
            return aOrder - bOrder;
          }
          // Secondary sort: By creation date (newest first) if status priority is the same
          const aDate = a.createdDate ? new Date(a.createdDate.split('.').reverse().join('-')) : new Date(0);
          const bDate = b.createdDate ? new Date(b.createdDate.split('.').reverse().join('-')) : new Date(0);
          return bDate - aDate; // Sort descending (newest first)
        }
      }
    };

    const orderBy = { id: "sortStatus", reversed: false };
    const filterBox = { input: "", visible: false };

    const getIcon = function (id) {
      if (orderBy.id === id) {
        if (orderBy.reversed) {
          return 'fa-sort-down';
        } else {
          return 'fa-sort-up';
        }
      } else {
        return 'fa-sort';
      }
    };

    const headersToHtml = function () {
      let html = '<table style="table-layout: fixed; width: 100%; border-collapse: collapse;"><tr>';
      Object.keys(headers).forEach(function(id) {
          const header = headers[id];
          html += `<th style="width: ${header.width}px; text-align: center; vertical-align: middle;">
                    <label class="content-new" style="width: 100%; margin: 0;">
                      ${header.toStr} <i id="${id}" class="btn-icon sort fas ${getIcon(id)}"></i>`;
          if (id === "sortUser") {
            html += `<i id="filterUser" class="btn-icon fas fa-filter"></i>
                     <span class="smallPopupContainer" id="userFilterSpan" style="display:none; display: block; margin-top: 5px;">
                       <input type="text" id="userNameBox" placeholder="Käyttäjätunnus" style="width: 80%;">
                     </span>`;
          }
          html += `</label></th>`;
      });
          html += '<th style="width: 160px; text-align: center; vertical-align: middle;"><button class="new btn btn-primary" style="margin-left: 20px;">Uusi tieosoiteprojekti</button></th></tr></table>';
      return html;
    };

    const projectList = $(`<div id="project-window" class="form-horizontal project-list"></div>`).hide();
    projectList.append(`<button class="close btn-close">x</button>`);
    projectList.append(`<div class="content">Tieosoiteprojektit</div>`);
    projectList.append(`<div class="content-new">${headersToHtml()}</div>`);
    projectList.append(`<div id="project-list" style="width:1000px; height:390px; overflow-y:auto; overflow-x:hidden;"></div>`);
    projectList.append(`<div class="content-footer">
      <label class="tr-visible-checkbox checkbox">
        <input type="checkbox" name="OldAcceptedProjectsVisible" value="OldAcceptedProjectsVisible" id="OldAcceptedProjectsVisibleCheckbox">
        Näytä kaikki tieverkolle päivitetyt projektit
      </label>
      <i id="sync" class="btn-icon btn-refresh fa fa-sync-alt" title="Päivitä lista"></i>
      </div>`);

    const staticFieldProjectList = function (dataField) {
      return `<div"><label class="control-label-projects-list">${dataField}</label></div>`;
    };

    let pollProjects = null;

    function show() {
      $('.container').append('<div class="modal-overlay confirm-modal" id="projectList"><div class="modal-dialog"></div></div>');
      $('.modal-dialog').append(projectList.show());
      $('#OldAcceptedProjectsVisibleCheckbox').prop('checked', false);
      eventbus.trigger("roadAddressProject:deactivateAllSelections");
      bindEvents();
      fetchProjects();
      pollProjects = setInterval(fetchProjectStates, 60 * 1000);
    }

    function hide() {
      filterBox.visible = false;
      $('#userNameBox').val('');
      $('#userFilterSpan').hide();
      projectList.hide();
      eventbus.trigger("roadAddressProject:startAllInteractions");
      $('.modal-overlay').remove();
      clearInterval(pollProjects);
    }

    function fetchProjects() { projectCollection.getProjects(onlyActive()); }
    function fetchProjectStates() { projectCollection.getProjectStates(projectArray.map((project) => project.id)); }
    function onlyActive() { return !$('#OldAcceptedProjectsVisibleCheckbox')[0].checked; }

    const filterByUser = function () {
      const input = $('#userNameBox').val();
      const rows = $('#project-list').find('tr');
      if (input === "") {
        rows.show();
        return;
      }
      rows.hide();
      rows.each(function () {
        const label = $(this).find('.innerCreatedBy').find("label").text();
        if (label.toLowerCase().indexOf(input.toLowerCase()) !== -1)
          $(this).show();
      });
    };

    const userFilterVisibility = function () {
      const searchBox = $('#userFilterSpan');
      const textField = $('#userNameBox');
      if (filterBox.visible) {
        searchBox.show();
        if (textField.val() === "") textField.val(applicationModel.getSessionUsername());
      } else {
        textField.val("");
        searchBox.hide();
      }
      filterByUser();
    };

    function bindEvents() {
      eventbus.on('roadAddressProjects:fetched', function (projects) {
        projectArray = projects.filter(proj => proj.statusCode !== projectStatus.Deleted.value);
        createProjectList(projectArray);
        userFilterVisibility();
        $('#sync').removeClass("btn-spin");
      });

      eventbus.on('roadAddressProjectStates:fetched', function (idsAndStates) {
        projectArray = projectArray.map((project) => {
          const stateEntry = idsAndStates.find((idState) => idState[0] === project.id);
          if (stateEntry) {
            project.statusCode = stateEntry[1];
            project.statusDescription = Object.values(ViiteEnumerations.ProjectStatus).find((enumState) => enumState.value === project.statusCode).description;
          }
          return project;
        });
        createProjectList(projectArray);
        userFilterVisibility();
        $('#sync').removeClass("btn-spin");
      });

      function sortProjects(projects) {
        return projects.slice().sort((a, b) => {
          const primaryCmp = headers[orderBy.id].sortFunc(a, b);
          const primaryCmpAdjusted = orderBy.reversed ? -primaryCmp : primaryCmp;
          if (primaryCmpAdjusted !== 0) return primaryCmpAdjusted;
          // Secondary sort by createdDate DESC (latest first)
          return new Date(b.createdDate) - new Date(a.createdDate);
        });
      }

      const createProjectList = function (projects) {
        const sortedProjects = sortProjects(projects);
        let html = '<table style="table-layout: fixed; width: 100%; border-collapse: collapse;">';

        if (sortedProjects.length) {
          sortedProjects.forEach(function(proj, index) {
            const info = proj.statusInfo || 'Ei lisätietoja';
            const openButton = proj.statusCode === projectStatus.ErrorInViite.value
                ? `<button style="margin-bottom: 6px !important;" class="project-open btn btn-new-error" id="reopen-project-${proj.id}" value="${proj.id}" data-projectStatus="${proj.statusCode}">Avaa uudelleen</button>`
                : `<button style="margin-bottom: 6px !important;" class="project-open btn btn-new" id="open-project-${proj.id}" value="${proj.id}" data-projectStatus="${proj.statusCode}">Avaa</button>`;

            html += `<tr id="${index}" class="project-item">
              <td style="text-align: left; vertical-align: middle;">${staticFieldProjectList(proj.name)}</td>
              <td style="text-align: center; vertical-align: middle;" title="${info}">${staticFieldProjectList(proj.evks)}</td>
              <td class="innerCreatedBy" style="text-align: center; vertical-align: middle;" title="${info}">${staticFieldProjectList(proj.createdBy)}</td>
              <td style="text-align: center; vertical-align: middle;" title="${info}">${staticFieldProjectList(dateutil.dateObjectToFinnishString(new Date(proj.createdDate)))}</td>
              <td style="text-align: center; vertical-align: middle;" title="${info}">${staticFieldProjectList(proj.statusDescription)}</td>
              <td style="text-align: center; vertical-align: middle;">
                <div style="display: flex; justify-content: center; align-items: center; height: 100%;">${openButton}</div>
              </td>
            </tr>`;
          });
        }
        html += '</table>';
        $('#project-list').html(html);

        $('[id*="open-project"]').click(function (event) {
          const button = $(this);
          const status = parseInt(button.attr("data-projectStatus"));
          const triggerOpening = function (e, b) {
            $('#OldAcceptedProjectsVisibleCheckbox').prop('checked', false);
            if (b.hasClass("btn-new-error")) {
              projectCollection.reOpenProjectById(parseInt(e.currentTarget.value));
              eventbus.once("roadAddressProject:reOpenedProject", () => openProjectSteps(e));
            } else {
              openProjectSteps(e);
            }
          };

          if (status === projectStatus.InUpdateQueue.value || status === projectStatus.UpdatingToRoadNetwork.value) {
            new GenericConfirmPopup("Projektin muokkaaminen ei ole mahdollista, koska sitä päivitetään tieverkolle. Haluatko avata sen?", {
              successCallback: () => { clearInterval(pollProjects); triggerOpening(event, button); }
            });
          } else {
            clearInterval(pollProjects);
            triggerOpening(event, button);
          }
        });
      };

      const openProjectSteps = function (event) {
        applicationModel.addSpinner();
        projectCollection.getProjectsWithLinksById(parseInt(event.currentTarget.value)).then(function (result) {
          eventbus.trigger('roadAddress:openProject', result);
          if (applicationModel.isReadOnly()) $('.edit-mode-btn:visible').click();
        });
      };

      projectList.on('click', '[id^=sort]', function (event) {
        const eventId = event.target.id;
        if (headers[eventId]) {
          orderBy.reversed = orderBy.id === eventId && !orderBy.reversed;
          orderBy.id = eventId;
          $('.content-new i.sort').removeClass('fa-sort-up fa-sort-down').addClass('fa-sort');
          $('#' + eventId).removeClass('fa-sort').addClass(getIcon(eventId));
          createProjectList(projectArray);
          filterByUser();
        }
      });

      $('#filterUser').click(() => { filterBox.visible = !filterBox.visible; userFilterVisibility(); });
      $('#OldAcceptedProjectsVisibleCheckbox').change(() => fetchProjects());
      projectList.on('click', 'button.new', function () {
        $('#OldAcceptedProjectsVisibleCheckbox').prop('checked', false);
        $('.project-list').append('<div class="modal-overlay confirm-modal"><div class="modal-dialog"></div></div>');
        clearInterval(pollProjects);
        eventbus.trigger('roadAddress:newProject');
        if (applicationModel.isReadOnly()) {
          $('.edit-mode-btn:visible').click();
        }
      });
      projectList.on('click', 'button.close', () => hide());
      $('#userNameBox').keyup(function () {
        filterByUser();
      });
      projectList.on('click', '#sync', function () {
        $('#sync').addClass("btn-spin"); // make the sync button spin
        fetchProjects();
      });
    }

    return { show, hide, element: projectList, bindEvents };
  };
}(this));