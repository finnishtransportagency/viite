(function (root) {
  root.ProjectListModel = function (projectCollection) {
    var projectStatus = ViiteEnumerations.ProjectStatus;
    var projectArray = [];
    var headers = {
      "sortName": {
        toStr: "PROJEKTIN NIMI", width: "255",
        sortFunc: function (a, b) {
          return a.name.localeCompare(b.name, 'fi');
        }
      },
      "sortELY": {
        toStr: "ELY", width: "50",
        sortFunc: function (a, b) {
          return a.elys[0] - ((b.elys.length > 0) ? b.elys[0] : -1);
        }
      },
      "sortUser": {
        toStr: "KÄYTTÄJÄ", width: "115",
        sortFunc: function (a, b) {
          return a.createdBy.localeCompare(b.createdBy, 'fi');
        }
      },
      "sortDate": {
        toStr: "LUONTIPVM", width: "110",
        sortFunc: function (a, b) {
          var aDate = a.createdDate.split('.').reverse().join('-');
          var bDate = b.createdDate.split('.').reverse().join('-');
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

    var orderBy = {
      id: "sortStatus", reversed: false
    };

    var filterBox = {
      input: "", visible: false
    };

    var getIcon = function (id) {
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

    var headersToHtml = function () {
      var html = "";
      _.forEach(Object.keys(headers), function(id) {
          var header = headers[id];
          html += '<label class="content-new label" style="width: ' + header.width + 'px">' + header.toStr + '<i id=' + id + ' class="btn-icon sort fas ' + getIcon(id) + '"></i>';
          if (id === "sortUser") {
            html += '<i id="filterUser" class="btn-icon fas fa-filter"></i></label>' +
              '<span class="smallPopupContainer" id="userFilterSpan" style="display:none">' +
              '<input type="text" id="userNameBox" placeholder="Käyttäjätunnus"></span>';
          }
          html += '</label>';
      });
      return html;
    };

    var projectList = $('<div id="project-window" class="form-horizontal project-list"></div>').hide();
    projectList.append('<button class="close btn-close">x</button>');
    projectList.append('<div class="content">Tieosoiteprojektit</div>');
    projectList.append('<div class="content-new">' +
      headersToHtml() +
      '<div class="actions">' +
      '<button class="new btn btn-primary" style="margin-top:-5px;">Uusi tieosoiteprojekti</button></div>' +
      '</div>');
    projectList.append('<div id="project-list" style="width:820px; height:390px; overflow:auto;"></div>');
    projectList.append('<div class="content-footer">' +
      '<label class="tr-visible-checkbox checkbox"><input type="checkbox" name="OldAcceptedProjectsVisible" value="OldAcceptedProjectsVisible" id="OldAcceptedProjectsVisibleCheckbox">Näytä kaikki tieverkolle päivitetyt projektit</label>' +
      '<i id="sync" class="btn-icon btn-refresh fa fa-sync-alt" title="Päivitä lista"></i>' +
      '</div>');

      var staticFieldProjectListElement = function (projectName) {
          const label = document.createElement("label");
          label.classList.add("control-label-projects-list");
          label.appendChild(document.createTextNode(projectName));
          return label;
      };

    var pollProjects = null;

    function show() {
      $('.container').append('<div class="modal-overlay confirm-modal" id="projectList"><div class="modal-dialog"></div></div>');
      $('.modal-dialog').append(projectList.show());
      eventbus.trigger("roadAddressProject:deactivateAllSelections");
      bindEvents();
      fetchProjects();
      // start polling projects evey 10 seconds
      pollProjects = setInterval(fetchProjectStates, 10 * 1000);
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
      projectCollection.getProjectStates(_.map(projectArray, "id"));
    }

    function onlyActive() {
      return !$('#OldAcceptedProjectsVisibleCheckbox')[0].checked;
    }

    var filterByUser = function () {
      var input = $('#userNameBox').val();
      var rows = $('#project-list').find('tr');
      if (input === "") {
        rows.show();
        return;
      }
      rows.hide();
      rows.each(function () {
        var label = $(this).find('.innerCreatedBy').find("label").text();
        if (label.toLowerCase().indexOf(input.toLowerCase()) !== -1)
          $(this).show();
      });
    };


    var userFilterVisibility = function () {
      var searchBox = $('#userFilterSpan');
      var textField = $('#userNameBox');
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
        projectArray = projects;
        createProjectList(projectArray);
        userFilterVisibility();
        $('#sync').removeClass("btn-spin"); // stop the sync button from spinning
      });

        eventbus.on('roadAddressProjectStates:fetched', function (idsAndStates) {
            projectArray = _.map(projectArray, (project) => {
                const statusCode = idsAndStates.find(idState => idState._1 === project.id)._2;
                project.statusCode = statusCode;
                project.statusDescription = Object.values(ViiteEnumerations.ProjectStatus).find(enumState => enumState.value === statusCode).description;
                return project;
            })
            createProjectList(projectArray);
            userFilterVisibility();
            $('#sync').removeClass("btn-spin"); // stop the sync button from spinning
        });


        var triggerOpening = function (event, button) {
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

      var createProjectList = function (projects) {
          var sortedProjects = projects.sort(function (a, b) {
              var cmp = (orderBy.reversed) ? headers[orderBy.id].sortFunc(b, a) : headers[orderBy.id].sortFunc(a, b);
              return (cmp === 0) ? a.name.localeCompare(b.name, 'fi') : cmp;
          });

          const tableElement = document.createElement("table");
          tableElement.style.tableLayout = "fixed";
          tableElement.style.width = "100%";

          function createTableRows(prjs, disp) {
              for (let uniqueId = 0; uniqueId < prjs.length; uniqueId++) {
                  const proj = prjs[uniqueId];
                  const info = (proj.statusInfo) ? proj.statusInfo : 'Ei lisätietoja';

                  const trElement = document.createElement("tr");
                  trElement.classList.add("project-item");
                  if (disp)
                    trElement.style.display = "None";
                  trElement.id = uniqueId.toString();

                  const tdElement1 = document.createElement("td");
                  tdElement1.classList.add("innerName");
                  tdElement1.style.width = "270px";
                  tdElement1.appendChild(staticFieldProjectListElement(proj.name));

                  const tdElement2 = document.createElement("td");
                  tdElement2.style.width = "60px";
                  tdElement2.style.wordBreak = "break-word";
                  tdElement2.setAttribute("title", info);
                  tdElement2.appendChild(staticFieldProjectListElement(proj.elys));

                  const tdElement3 = document.createElement("td");
                  tdElement3.classList.add("innerCreatedBy");
                  tdElement3.style.width = "120";
                  tdElement3.setAttribute("title", info);
                  tdElement3.appendChild(staticFieldProjectListElement(proj.createdBy));

                  const tdElement4 = document.createElement("td");
                  tdElement4.style.width = "120";
                  tdElement4.setAttribute("title", info);
                  tdElement4.appendChild(staticFieldProjectListElement(proj.createdDate));


                  const tdElement5 = document.createElement("td");
                  tdElement5.style.width = "120";
                  tdElement5.setAttribute("title", info);
                  tdElement5.appendChild(staticFieldProjectListElement(proj.statusDescription));

                  const tdElement6 = document.createElement("td");
                  tdElement6.id = "innerOpenProjectButton";

                  const element6Button = document.createElement("button");
                  element6Button.value = proj.id
                  element6Button.classList.add("project-open");
                  element6Button.classList.add("btn");
                  element6Button.style.alignment = "right";
                  element6Button.style.marginBottom = "6px";
                  element6Button.setAttribute("data-projectStatus", proj.statusCode);
                  switch (proj.statusCode) {
                      case projectStatus.ErrorInViite.value:
                          element6Button.innerText = "Avaa uudelleen";
                          element6Button.classList.add("btn-new-error");
                          element6Button.style.marginLeft = "25px";
                          element6Button.id = "reopen-project" + proj.id;
                          break;
                      default:
                          element6Button.innerText = "Avaa";
                          element6Button.classList.add("btn-new");
                          element6Button.style.marginLeft = "50px";
                          element6Button.id = "open-project" + proj.id;
                  }
                  tdElement6.appendChild(element6Button)
                  trElement.append(tdElement1, tdElement2, tdElement3, tdElement4, tdElement5, tdElement6);
                  tableElement.appendChild(trElement)

              };

              const el = $('#project-list > table');
              if (el.length === 1)
                  el.replaceWith(tableElement);
              else
                  $('#project-list').append(tableElement);

              return Promise.resolve();
          }

          // Split for long lists.
          let firstN =  10;
          createTableRows(_.take(sortedProjects, firstN), false).then(
              () => {
                  createTableRows(_.drop(sortedProjects, firstN), true);
              });

          $(document).ready(function() {
              $(".project-item").show();
              $('[id*="open-project"]').click(function (event) {
                var button = $(this);
                if (parseInt(button.attr("data-projectStatus")) === projectStatus.InUpdateQueue.value ||
                    parseInt(button.attr("data-projectStatus")) === projectStatus.UpdatingToRoadNetwork.value) {
                  new GenericConfirmPopup("Projektin muokkaaminen ei ole mahdollista, koska sitä päivitetään tieverkolle. Haluatko avata sen?", {
                    successCallback: function () {
                      clearInterval(pollProjects);
                      triggerOpening(event, button);
                    },
                    closeCallback: function () {
                    }
                  });
                } else {
                  clearInterval(pollProjects);
                  triggerOpening(event, button);
                }
              });
          });
      };

      $('#filterUser').click(function () {
        filterBox.visible = !filterBox.visible;
        userFilterVisibility();
      });

      var openProjectSteps = function (event) {
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
        var eventId = event.target.id;
        _.forEach(Object.keys(headers), function(id) {
          var icon = 'fa-sort';
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
