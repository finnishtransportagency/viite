(function (root) {
  root.ProjectListModel = function (projectCollection) {
    var projectStatus = LinkValues.ProjectStatus;
    var projectList = $('<div id="project-window" class="form-horizontal project-list"></div>').hide();
    projectList.append('<button class="close btn-close">x</button>');
    projectList.append('<div class="content">Tieosoiteprojektit</div>');
    projectList.append('<div class="content-new">' +
      '<label class="content-new label">PROJEKTIN NIMI</label>' +
      '<label class="content-new label" style="width: 100px">ELY</label>' +
        '<label id="userSearch" class="content-new label" style="width: 100px">KÄYTTÄJÄ</label>' +
        '<span class="smallPopupContainer" id="userFilterSpan" style="display:none">' +
        '<input type="text" id="userNameBox" placeholder="Käyttäjätunnus">' +
        '</span>' +
      '<label class="content-new label" style="width: 100px">TILA</label>' +
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

    var filterByUser = function () {
      var input = $('#userNameBox').val();
      var rows = $('#project-list').find('tr');
      if (input === "") {
        rows.show();
        return;
      }
      rows.hide();
      var filteredRowIds = _.uniq(rows.filter(function () {
        var $t = $(this);
        return $t.find('#innerCreatedBy').find("label").text().toLowerCase().indexOf(input.toLowerCase()) !== -1;
      }).map(function () {
        var row = $(this);
        return row.attr('id');
      }));
      rows.filter(function () {
        var $t = $(this);
        return _.contains(filteredRowIds, $t.attr('id'));
      }).show();
    };


    var userFilterVisibility = function (showFilters) {
      if (showFilters) {
        $('#userFilterSpan').css("display", "block");
        $('#userFilterSpan').css("visibility", "visible");
        if ($('#userNameBox').val() === "") {
          $('#userNameBox').val(applicationModel.getSessionUser());
          filterByUser();
        }
      } else {
        $('#userFilterSpan').css("display", "none");
        $('#userFilterSpan').css("visibility", "hidden");

      }
    };

    function bindEvents() {

      eventbus.once('roadAddressProjects:fetched', function(projects) {
        var unfinishedProjects = _.filter(projects, function(proj) {
          return (proj.statusCode >= 1 && proj.statusCode <= 5) || proj.statusCode === 8;
        });
        var html = '<table style="align-content: left; align-items: left; table-layout: fixed; width: 100%;">';
        if (!_.isEmpty(unfinishedProjects)) {
          var uniqueId = 0;
          _.each(unfinishedProjects, function(proj) {
            var info = typeof(proj.statusInfo) !== "undefined" ? proj.statusInfo : 'Ei lisätietoja';
            html += '<tr id="' + uniqueId + '" class="project-item">' +
                '<td id="innerName" style="width: 310px;">' + staticFieldProjectName(proj.name) + '</td>' +
                '<td id="innerEly" style="width: 110px;" title="' + info + '">' + staticFieldProjectList(proj.ely) + '</td>' +
                '<td id="innerCreatedBy" style="width: 110px;" title="' + info + '">' + staticFieldProjectList(proj.createdBy) + '</td>' +
                '<td id="innerStatus" style="width: 110px;" title="' + info + '">' + staticFieldProjectList(proj.statusDescription) + '</td>';
            if (proj.statusCode === projectStatus.ErrorInViite.value) {
              html += '<td>' + '<button class="project-open btn btn-new-error" style="alignment: right; margin-bottom: 6px; margin-left: 45px; visibility: hidden">Avaa uudelleen</button>' + '</td>' +
                  '</tr>' + '<tr style="border-bottom: 1px solid darkgray;"><td colspan="100%"></td></tr>';
            } else if (proj.statusCode === projectStatus.ErroredInTR.value) {
              html += '<td id="innerOpenProjectButton">' + '<button class="project-open btn btn-new-error" style="alignment: right; margin-bottom: 6px; margin-left: 45px" id="reopen-project-' + proj.id + '" value="' + proj.id + '">Avaa uudelleen</button>' + '</td>' +
                '</tr>' + '<tr style="border-bottom: 1px solid darkgray;"><td colspan="100%"></td></tr>';
            } else {
              html += '<td id="innerOpenProjectButton">' + '<button class="project-open btn btn-new" style="alignment: right; margin-bottom: 6px; margin-left: 70px" id="open-project-' + proj.id + '" value="' + proj.id + '">Avaa</button>' + '</td>' +
                  '</tr>' + '<tr id="' + uniqueId + '" style="border-bottom: 1px solid darkgray;"><td colspan="100%"></td></tr>';
            }
            uniqueId = uniqueId + 1;
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
        $('#userSearch').click(function () {
          var spanIsInvisible = $('#userFilterSpan').css('display') === 'none';
          userFilterVisibility(spanIsInvisible);
        });
      });

      var openProjectSteps = function(event) {
        projectCollection.getProjectsWithLinksById(parseInt(event.currentTarget.value)).then(function(result){
          setTimeout(function(){}, 0);
          eventbus.trigger('roadAddress:openProject', result);
          if(applicationModel.isReadOnly()) {
            $('.edit-mode-btn:visible').click();
          }
        });
      };

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
        userFilterVisibility(false);
        $('#project-list').find('table').remove();
        $('.project-item').remove();
        hide();
      });

      $('#userNameBox').keyup(function () {
        filterByUser();
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
