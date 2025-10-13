/**
 * @typedef {Object} Project
 * @property {string} name
 * @property {number} statusCode
 * @property {string} statusDescription
 * @property {string} createdBy
 * @property {string} createdDate
 * @property {string} [statusInfo]
 * @property {number[]} elys
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
        toStr: "PROJEKTIN NIMI", width: "200",
        sortFunc: function (a, b) {
          return a.name.localeCompare(b.name, 'fi');
        }
      },
      "sortELY": {
        toStr: "ELY", width: "50",
        sortFunc: function (a, b) {
          let i = 0;
          while (i < a.elys.length && i < b.elys.length) {
            if (a.elys[i] !== b.elys[i]) {
              return a.elys[i] - b.elys[i];
            }
            i++;
          }
          return a.elys.length - b.elys.length;
        }
      },
      "sortEVK": {
        toStr: "ELINVOIMAKESKUS", width: "180",
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
        toStr: "KÄYTTÄJÄ", width: "115",
        sortFunc: function (a, b) {
          return a.createdBy.localeCompare(b.createdBy, 'fi');
        }
      },
      "sortDate": {
        toStr: "LUONTIPVM", width: "158",
        sortFunc: function (a, b) {
          const aDate = a.createdDate.split('.').reverse().join('-');
          const bDate = b.createdDate.split('.').reverse().join('-');
          return new Date(bDate) - new Date(aDate);
        }
      },
      "sortStatus": {
        toStr: "TILA", width: "60",
        sortFunc: function (a, b) {
          return a.statusCode - b.statusCode;
        }
      }
    };

    const orderBy = {
      id: "sortStatus", reversed: false
    };

    const filterBox = {
      input: "", visible: false
    };

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
      let html = "";
      Object.keys(headers).forEach(function(id) {
          const header = headers[id];
          html += '<label class="content-new label" style="width: ' + header.width + 'px">' + header.toStr + '<i id="' + id + '" class="btn-icon sort fas ' + getIcon(id) + '"></i>';

        if (id === "sortUser") {
            html += '<i id="filterUser" class="btn-icon fas fa-filter"></i></label>' +
              '<span class="smallPopupContainer" id="userFilterSpan" style="display:none">' +
              '<input type="text" id="userNameBox" placeholder="Käyttäjätunnus"></span>';
          }
          html += '</label>';
      });
      return html;
    };

    const projectList = $('<div id="project-window" class="form-horizontal project-list"></div>').hide();
    projectList.append('<button class="close btn-close">x</button>');
    projectList.append('<div class="content">Tieosoiteprojektit</div>');
    projectList.append('<div class="content-new">' +
      headersToHtml() +
      '<div class="actions">' +
      '<button class="new btn btn-primary" style="margin-top:-5px;">Uusi tieosoiteprojekti</button></div>' +
      '</div>');
    projectList.append('<div id="project-list" style="width:1000px; height:390px; overflow:auto;"></div>');
    projectList.append('<div class="content-footer">' +
      '<label class="tr-visible-checkbox checkbox"><input type="checkbox" name="OldAcceptedProjectsVisible" value="OldAcceptedProjectsVisible" id="OldAcceptedProjectsVisibleCheckbox">Näytä kaikki tieverkolle päivitetyt projektit</label>' +
      '<i id="sync" class="btn-icon btn-refresh fa fa-sync-alt" title="Päivitä lista"></i>' +
      '</div>');

    const staticFieldProjectName = function (dataField) {
      return '<div>' +
          '<label class="control-label-projects-list" style="width: 300px">' + dataField + '</label>' +
          '</div>';
    };

    const staticFieldProjectList = function (dataField) {
      return '<div>' +
          '<label class="control-label-projects-list">' + dataField + '</label>' +
          '</div>';
      };

    let pollProjects = null;

    function show() {
      $('.container').append('<div class="modal-overlay confirm-modal" id="projectList"><div class="modal-dialog"></div></div>');
      $('.modal-dialog').append(projectList.show());
      eventbus.trigger("roadAddressProject:deactivateAllSelections");
      bindEvents();
      fetchProjects();
      // start polling projects evey 60 seconds
      pollProjects = setInterval(fetchProjectStates, 60 * 1000);
    }

    function hide() {
      projectList.hide();
      eventbus.trigger("roadAddressProject:startAllInteractions");
      $('.modal-overlay').remove();
      clearInterval(pollProjects);
    }

    function fetchProjects() {
      projectCollection.getProjects(onlyActive());
    }

    function fetchProjectStates() {
      projectCollection.getProjectStates(projectArray.map((project) => project.id));
    }

    function onlyActive() {
      return !$('#OldAcceptedProjectsVisibleCheckbox')[0].checked;
    }

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
        if (textField.val() === "") {
          textField.val(applicationModel.getSessionUsername());
        }
      } else {
        textField.val("");
        searchBox.hide();
      }
      filterByUser();
    };

    function bindEvents() {

      eventbus.on('roadAddressProjects:fetched', function (projects) {
        projectArray = projects.filter(function(proj) {
          return proj.statusCode !== projectStatus.Deleted.value; //filter deleted projects out
        });
        createProjectList(projectArray);
        userFilterVisibility();
        $('#sync').removeClass("btn-spin"); // stop the sync button from spinning
      });

      eventbus.on('roadAddressProjectStates:fetched', function (idsAndStates) {
        projectArray = projectArray.map((project) => {
          const statusCode = idsAndStates.find((idState) => idState[0] === project.id)[1];
          project.statusCode = statusCode;
          project.statusDescription = Object.values(ViiteEnumerations.ProjectStatus).find((enumState) => enumState.value === statusCode).description;
          return project;
        });
        createProjectList(projectArray);
        userFilterVisibility();
        $('#sync').removeClass("btn-spin"); // stop the sync button from spinning
      });

      // Helper: Compare projects by createdDate (newest first)
      function compareByCreatedDateDesc(a, b) {
        const dateA = new Date(a.createdDate);
        const dateB = new Date(b.createdDate);
        return dateB - dateA;
      }

      // Main sort function for the project list
      function sortProjects(projects) {
        const sortedProjects = projects.slice().sort((a, b) => {
          const primaryCmp = headers[orderBy.id].sortFunc(a, b);

          // Adjust primary comparison based on reversed flag
          const primaryCmpAdjusted = orderBy.reversed ? -primaryCmp : primaryCmp;

          if (primaryCmpAdjusted !== 0) return primaryCmpAdjusted;

          // Secondary sort by createdDate DESC (latest first)
          return compareByCreatedDateDesc(a, b);
        });

        // Special case: if sorting by statusCode ascending and not reversed
        if (orderBy.id === 'sortStatus' && !orderBy.reversed) {
          return projects.slice().sort((a, b) => {
            if (a.statusCode !== b.statusCode) {
              return a.statusCode - b.statusCode;
            }
            return compareByCreatedDateDesc(a, b);
          });
        }

        return sortedProjects;
      }


      const createProjectList = function (projects) {
        const sortedProjects = sortProjects(projects, orderBy, headers);

        const triggerOpening = function (event, button) {
          $('#OldAcceptedProjectsVisibleCheckbox').prop('checked', false);
          if (button.length > 0 && button[0].className === "project-open btn btn-new-error") {
            projectCollection.reOpenProjectById(parseInt(event.currentTarget.value));
            eventbus.once("roadAddressProject:reOpenedProject", function (_successData) {
              openProjectSteps(event);
            });
          } else {
            openProjectSteps(event);
          }
        };

        let html = '<table style="table-layout: fixed; width: 100%;">';

        if (sortedProjects.length) {
          let uniqueId = 0;

          sortedProjects.forEach(function(proj) {
            const info = proj.statusInfo || 'Ei lisätietoja';
            html += `<tr id="${uniqueId}" class="project-item">
              <td class="innerName" style="width: 200px; vertical-align: middle;">${staticFieldProjectName(proj.name)}</td>
              <td style="width: 50px; text-align: center; vertical-align: middle;" title="${info}">${staticFieldProjectList(proj.elys)}</td>
              <td style="width: 180px; text-align: center; vertical-align: middle; padding-right: 48px;" title="${info}">${staticFieldProjectList(proj.evks)}</td>
              <td class="innerCreatedBy" style="width: 100px; vertical-align: middle;" title="${info}">${staticFieldProjectList(proj.createdBy)}</td>
              <td style="width: 100px; text-align: center; vertical-align: middle; padding-right: 48px" title="${info}">${staticFieldProjectList(dateutil.dateObjectToFinnishString(new Date(proj.createdDate)))}</td>
              <td style="width: 80px; text-align: center; vertical-align: middle;" title="${info}">${staticFieldProjectList(proj.statusDescription)}</td>`;

            const openButton = proj.statusCode === projectStatus.ErrorInViite.value
                ? `<button class="project-open btn btn-new-error" style="margin-bottom: 6px; margin-left: 25px" id="reopen-project-${proj.id}" value="${proj.id}" data-projectStatus="${proj.statusCode}">Avaa uudelleen</button>`
                : `<button class="project-open btn btn-new" style="margin-bottom: 6px; margin-left: 50px" id="open-project-${proj.id}" value="${proj.id}" data-projectStatus="${proj.statusCode}">Avaa</button>`;

            html += `<td id="innerOpenProjectButton">${openButton}</td></tr>`;

            uniqueId += 1;
          });

          html += '</table>';
          $('#project-list').html(html);

          $('[id*="open-project"]').click(function (event) {
            const button = $(this);
            const status = parseInt(button.attr("data-projectStatus"));
            if (status === projectStatus.InUpdateQueue.value || status === projectStatus.UpdatingToRoadNetwork.value) {
              new GenericConfirmPopup("Projektin muokkaaminen ei ole mahdollista, koska sitä päivitetään tieverkolle. Haluatko avata sen?", {
                successCallback: function () {
                  clearInterval(pollProjects);
                  triggerOpening(event, button);
                },
                closeCallback: function () {}
              });
            } else {
              clearInterval(pollProjects);
              triggerOpening(event, button);
            }
          });

        } else {
          html += '</table>';
          $('#project-list').html(html);
        }
      };

      $('#filterUser').click(function () {
        filterBox.visible = !filterBox.visible;
        userFilterVisibility();
      });

      const openProjectSteps = function (event) {
        applicationModel.addSpinner();
        projectCollection.getProjectsWithLinksById(parseInt(event.currentTarget.value)).then(function (result) {
          setTimeout(function () {
          }, 0);
          eventbus.trigger('roadAddress:openProject', result);
          if (applicationModel.isReadOnly()) {
            $('.edit-mode-btn:visible').click();
          }
        });
      };

      /*
      User can sort project list by clicking the sort arrows next to column headers. By clicking same arrows again, user can reverse the order.
       */
      projectList.on('click', '[id^=sort]', function (event) {
        const eventId = event.target.id;
        Object.keys(headers).forEach(function(id) {
          let icon = 'fa-sort';
          if (id === eventId) {
            orderBy.reversed = orderBy.id === id && !orderBy.reversed;
            orderBy.id = id;
            icon = getIcon(id);
          }
          $('#' + id).removeClass('fa-sort fa-sort-up fa-sort-down').addClass(icon);
          // Update classes
        });
        // Create project list
        createProjectList(projectArray);
        filterByUser();
      });

      $('#OldAcceptedProjectsVisibleCheckbox').change(function () {
        fetchProjects();
      });

      projectList.on('click', 'button.cancel', function () {
        hide();
      });

      projectList.on('click', 'button.new', function () {
        $('#OldAcceptedProjectsVisibleCheckbox').prop('checked', false);
        $('.project-list').append('<div class="modal-overlay confirm-modal"><div class="modal-dialog"></div></div>');
        clearInterval(pollProjects);
        eventbus.trigger('roadAddress:newProject');
        if (applicationModel.isReadOnly()) {
          $('.edit-mode-btn:visible').click();
        }
      });

      projectList.on('click', 'button.close', function () {
        $('#project-list').find('table').hide();
        $('.project-item').remove();
        $('#OldAcceptedProjectsVisibleCheckbox').prop('checked', false);
        hide();
      });

      $('#userNameBox').keyup(function () {
        filterByUser();
      });

      projectList.on('click', '#sync', function () {
        $('#sync').addClass("btn-spin"); // make the sync button spin
        fetchProjects();
      });
    }

    return {
      show: show,
      hide: hide,
      element: projectList,
      bindEvents: bindEvents
    };
  };
}(this));
