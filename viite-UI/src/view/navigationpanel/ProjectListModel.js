(function (root) {
  root.ProjectListModel = function (projectCollection) {
    var projectStatus = LinkValues.ProjectStatus;
    var projectArray = [];
    var listSortedBy = '';
    var order = 1; // 1 -> ascending order, -1 -> descending order
    var projectList = $('<div id="project-window" class="form-horizontal project-list"></div>').hide();
    projectList.append('<button class="close btn-close">x</button>');
    projectList.append('<div class="content">Tieosoiteprojektit</div>');
    projectList.append('<div class="content-new">' +
      '<label id="sortName" class="content-new label" style="width: 255px">PROJEKTIN NIMI</label>' +
      '<label id="sortELY" class="content-new label" style="width: 50px">ELY</label>' +
      '<label id="sortUser" class="content-new label" style="width: 110px">KÄYTTÄJÄ</label>' +
      '<label id="sortDate" class="content-new label" style="width: 100px">ALKUPVM</label>' +
      '<label id="sortStatus" class="content-new label" style="width: 60px">TILA</label>' +
      '<div class="actions">' +
    '<button class="new btn btn-primary" style="margin-top:-5px;">Uusi tieosoiteprojekti</button></div>' +
      '</div>');
    projectList.append('<div id="project-list" style="width:810px; height:400px; overflow:auto;"></div>');

    var staticFieldProjectName = function(dataField) {
      var field;
      field = '<div>' +
        '<label class="control-label-projects-list" style="width: 300px">' + dataField + '</label>' +
        '</div>';
      return field;
    };

    var staticFieldProjectList = function(dataField) {
      var field;
      field = '<div>' +
        '<label class="control-label-projects-list">' + dataField + '</label>' +
        '</div>';
      return field;
    };

    function toggle() {
      $('.container').append('<div class="modal-overlay confirm-modal"><div class="modal-dialog"></div></div>');
      $('.modal-dialog').append(projectList.toggle());
      eventbus.trigger("roadAddressProject:deactivateAllSelections");
      bindEvents();
      fetchProjects();
    }

    function hide() {
      projectList.hide();
      eventbus.trigger("roadAddressProject:startAllInteractions");
      $('.modal-overlay').remove();
    }

    function fetchProjects(){
      projectCollection.getProjects();
    }

    function bindEvents() {

      eventbus.once('roadAddressProjects:fetched', function(projects) {
        projectArray = projects;
        createProjectList(projects);
      });

      var createProjectList = function(projects) {
        var unfinishedProjects = _.filter(projects, function(proj) {
          return (proj.statusCode >= 1 && proj.statusCode <= 5) || proj.statusCode === 8;
        });
        var html = '<table style="align-content: left; align-items: left; table-layout: fixed; width: 100%;">';
          if (!_.isEmpty(unfinishedProjects)) {
            _.each(unfinishedProjects, function(proj) {
              var info = typeof(proj.statusInfo) !== "undefined" ? proj.statusInfo : 'Ei lisätietoja';
              html += '<tr class="project-item">' +
                '<td style="width: 270px;">' + staticFieldProjectName(cutProjectName(proj.name)) + '</td>' +
                '<td style="width: 60px;" title="' + info + '">' + staticFieldProjectList(proj.ely) + '</td>' +
                '<td style="width: 120px;" title="' + info + '">' + staticFieldProjectList(proj.createdBy) + '</td>' +
                '<td style="width: 110px;" title="' + info + '">' + staticFieldProjectList(proj.startDate) + '</td>' +
                '<td style="width: 70px;" title="' + info + '">' + staticFieldProjectList(proj.statusDescription) + '</td>';
              if (proj.statusCode === projectStatus.ErrorInViite.value) {
                html += '<td>' + '<button class="project-open btn btn-new-error" style="alignment: right; margin-bottom: 6px; margin-left: 45px; visibility: hidden">Avaa uudelleen</button>' + '</td>' +
                  '</tr>' + '<tr style="border-bottom: 1px solid darkgray;"><td colspan="100%"></td></tr>';
              } else if (proj.statusCode === projectStatus.ErroredInTR.value) {
                html += '<td>' + '<button class="project-open btn btn-new-error" style="alignment: right; margin-bottom: 6px; margin-left: 45px" id="reopen-project-' + proj.id + '" value="' + proj.id + '">Avaa uudelleen</button>' + '</td>' +
                  '</tr>' + '<tr style="border-bottom: 1px solid darkgray;"><td colspan="100%"></td></tr>';
              } else {
                html += '<td>' + '<button class="project-open btn btn-new" style="alignment: right; margin-bottom: 6px; margin-left: 80px" id="open-project-' + proj.id + '" value="' + proj.id + '">Avaa</button>' + '</td>' +
                  '</tr>' + '<tr style="border-bottom: 1px solid darkgray;"><td colspan="100%"></td></tr>';
              }
            });
            html += '</table>';
            $('#project-list').html($(html));
            $('[id*="open-project"]').click(function(event) {
              if (this.className === "project-open btn btn-new-error") {
                projectCollection.reOpenProjectById(parseInt(event.currentTarget.value));
                eventbus.once("roadAddressProject:reOpenedProject", function(successData) {
                  openProjectSteps(event);
                });
              } else {
                openProjectSteps(event);
              }
            });
          } else {
            html += '</table>';
            $('#project-list').html($(html));
          }
          applicationModel.removeSpinner();
      };

      var openProjectSteps = function(event) {
        projectCollection.getProjectsWithLinksById(parseInt(event.currentTarget.value)).then(function(result){
          setTimeout(function(){}, 0);
          eventbus.trigger('roadAddress:openProject', result);
          if(applicationModel.isReadOnly()) {
            $('.edit-mode-btn:visible').click();
          }
        });
      };

      var cutProjectName = function (name) {
        var maxNameLength = 24;
        return name.length > maxNameLength ? name.substring(0, maxNameLength) + "..." : name;
      };

      /*
      User can sort project list by clicking column header label. By clicking same label again, user can reverse the order.
       */
      projectList.on('click', '[id^=sort]', function (event) {
        $('#project-list').empty();
        var id = event.target.id;
        order = (listSortedBy === id) ? order * -1 : 1; // keeps track if the list should be sorted ascending or descending
        listSortedBy = id;
        // Choose a function based on which label the user has pressed
        var func;
        switch (id) {
          case 'sortName':
            func = function(a,b) {
              return a.name.localeCompare(b.name, 'fi');
            };
            break;
          case 'sortELY':
            func = function(a,b) {
              return a.ely - b.ely;
            };
            break;
          case 'sortUser':
            func = function(a,b) {
              return a.createdBy.localeCompare(b.createdBy, 'fi');
            };
            break;
            case 'sortDate': // Dates can't be sorted in DD.MM.YYYY format so here the format is changed to YYYY-MM-DD
            func = function(a,b) {
              var aDate = a.startDate.split('.').reverse().join('-');
              var bDate = b.startDate.split('.').reverse().join('-');
              return new Date(aDate) - new Date(bDate);
            };
            break;
          case 'sortStatus':
            func = function(a,b) {
              return a.statusCode - b.statusCode;
            };
        }
        var cmp = 0;
        // Use the function chosen to sort the list
        createProjectList(projectArray.sort(function (a, b) {
          cmp = func(a,b);
          return (cmp !== 0) ? cmp * order : a.name.localeCompare(b.name, 'fi');
        }));
      });

      projectList.on('click', 'button.cancel', function() {
        hide();
      });

      projectList.on('click', 'button.new', function() {
        $('.project-list').append('<div class="modal-overlay confirm-modal"><div class="modal-dialog"></div></div>');
        eventbus.trigger('roadAddress:newProject');
        if(applicationModel.isReadOnly()) {
          $('.edit-mode-btn:visible').click();
        }
      });

      projectList.on('click', 'button.close', function() {
        $('.project-item').remove();
        hide();
      });
    }

    return {
      toggle: toggle,
      hide: hide,
      element: projectList,
      bindEvents: bindEvents
    };
  };
})(this);
