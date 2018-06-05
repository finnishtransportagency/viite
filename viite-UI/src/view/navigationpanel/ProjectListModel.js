(function (root) {
  root.ProjectListModel = function (projectCollection) {
    var projectStatus = LinkValues.ProjectStatus;
    var projectArray = [];
    var headers = {
      "sortName": {toStr: "PROJEKTIN NIMI ", width: "255", htmlClass: "fa-sort", order: 0,
        sortFunc: function(a,b) {
          return a.name.localeCompare(b.name, 'fi');
        }},
      "sortELY": {toStr: "ELY ", width: "50", htmlClass: "fa-sort-up", order: 1,
        sortFunc: function(a,b) {
            return a.ely - b.ely;
        }},
      "sortUser": {toStr: "KÄYTTÄJÄ ", width: "110", htmlClass: "fa-sort", order: 0,
        sortFunc: function(a,b) {
            return a.createdBy.localeCompare(b.createdBy, 'fi');
        }},
      "sortDate": {toStr: "ALKUPVM ", width: "100", htmlClass: "fa-sort", order: 0,
        sortFunc: function(a,b) {
            var aDate = a.startDate.split('.').reverse().join('-');
            var bDate = b.startDate.split('.').reverse().join('-');
            return new Date(aDate) - new Date(bDate);
        }},
      "sortStatus": {toStr: "TILA ", width: "60", htmlClass: "fa-sort", order: 0,
        sortFunc: function(a,b) {
            return a.statusCode - b.statusCode;
        }}
    };
    var headersToHtml = function() {
      var html = "";
      for (var id in headers) {
        var header = headers[id];
        html += `<label class="content-new label" style="width: ${header.width}px">${header.toStr}<i id="${id}" class="sort fas ${header.htmlClass}"></i></label>`
      }
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
      $('.sort').each( function() { // Initialize sorting -> projects are sorted by ELY
        var icon = $(this);
        icon.removeClass('fa-sort fa-sort-up fa-sort-down');
        icon.addClass(icon[0].id === "sortELY" ? 'fa-sort-up' : 'fa-sort');
      });
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
                '<td style="width: 270px;">' + staticFieldProjectName(proj.name) + '</td>' +
                '<td style="width: 60px;" title="' + info + '">' + staticFieldProjectList(proj.ely) + '</td>' +
                '<td style="width: 120px;" title="' + info + '">' + staticFieldProjectList(proj.createdBy) + '</td>' +
                '<td style="width: 110px;" title="' + info + '">' + staticFieldProjectList(proj.startDate) + '</td>' +
                '<td style="width: 70px;" title="' + info + '">' + staticFieldProjectList(proj.statusDescription) + '</td>';
              switch (proj.statusCode) {
                case projectStatus.ErrorInViite.value:
                  html += '<td>' + '<button class="project-open btn btn-new-error" style="alignment: right; margin-bottom: 6px; margin-left: 45px; visibility: hidden">Avaa uudelleen</button>' + '</td>'
                  break;
                case projectStatus.ErroredInTR.value:
                  html += '<td>' + '<button class="project-open btn btn-new-error" style="alignment: right; margin-bottom: 6px; margin-left: 45px" id="reopen-project-' + proj.id + '" value="' + proj.id + '">Avaa uudelleen</button>' + '</td>'
                  break;
                default:
                  html += '<td>' + '<button class="project-open btn btn-new" style="alignment: right; margin-bottom: 6px; margin-left: 80px" id="open-project-' + proj.id + '" value="' + proj.id + '">Avaa</button>' + '</td>'
              }
              html += '</tr>' + '<tr style="border-bottom: 1px solid darkgray;"><td colspan="100%"></td></tr>';
            });
            html += '</table>';
            $('#project-list').html(html);
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
            $('#project-list').html(html);
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

      /*
      User can sort project list by clicking the sort arrows next to column headers. By clicking same arrows again, user can reverse the order.
       */
      projectList.on('click', '[id^=sort]', function (event) {
        $('#project-list').empty();
        var id = event.target.id;
        for (var key in headers) { // Set right HTML classes for every headers' sort arrows based on their order values
          var header = headers[key]
          switch(key) {
              case id :
                header.order = header.order === 0 ? 1 : header.order * -1;
                header.htmlClass = header.order === 1 ? 'fa-sort-up' : 'fa-sort-down';
                break;
              default:
                header.order = 0;
                header.htmlClass = 'fa-sort';
          }
        }
        // Update classes
        $('.sort').removeClass('fa-sort-up fa-sort-down').addClass('fa-sort');
        $(`#${id}`).toggleClass(`fa-sort ${headers[id].htmlClass}`);
        // Sort and create project list
        createProjectList(projectArray.sort(function (a, b) {
          var cmp = headers[id].sortFunc(a,b);
          return (cmp !== 0) ? cmp * headers[id].order : a.name.localeCompare(b.name, 'fi');
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
