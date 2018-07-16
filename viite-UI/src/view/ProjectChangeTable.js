(function(root) {
  root.ProjectChangeTable = function(projectChangeInfoModel, projectCollection) {

    var changeTypes = [
      'Käsittelemättä',
      'Ennallaan',
      'Uusi',
      'Siirto',
      'Numerointi',
      'Lakkautettu'
    ];

    var LinkStatus = LinkValues.LinkStatus;
    var ProjectStatus = LinkValues.ProjectStatus;
    var windowMaximized = false;

    var changeTable =
      $('<div class="change-table-frame"></div>');
    // Text about validation success hard-coded now
    var changeTableHeader = $('<div class="change-table-fixed-header"></div>');
    changeTableHeader.append('<div class="change-table-header font-resize">Validointi ok. Alla näet muutokset projektissa.</div>');
    changeTableHeader.append('<button class="close wbtn-close">Sulje <i class="fas fa-window-close"></i></button>');
    changeTableHeader.append('<button class="max wbtn-max"><span id="buttonText">Suurenna </span><span id="sizeSymbol" style="font-size: 175%;font-weight: 900;">□</span></button>');
    changeTableHeader.append('<div class="change-table-borders">' +
      '<div id ="change-table-borders-changetype"></div>' +
      '<div id ="change-table-borders-source"></div>' +
      '<div id ="change-table-borders-reversed"></div>' +
      '<div id ="change-table-borders-target"></div></div>');
    changeTableHeader.append('<div class="change-table-sections">' +
      '<label class="change-table-heading-label" id="label-type">Ilmoitus</label>' +
      '<label class="change-table-heading-label" id="label-source">Nykyosoite</label>' +
      '<label class="change-table-heading-label" id="label-reverse"></label>' +
      '<label class="change-table-heading-label" id="label-target">Uusi osoite</label>');
    changeTableHeader.append('<div class="change-header">' +
      '<label class="project-change-table-dimension-header">TIE</label>' +
      '<label class="project-change-table-dimension-header">AJR</label>' +
      '<label class="project-change-table-dimension-header">OSA</label>' +
      '<label class="project-change-table-dimension-header">AET</label>' +
      '<label class="project-change-table-dimension-header">LET</label>' +
      '<label class="project-change-table-dimension-header">PIT</label>' +
      '<label class="project-change-table-dimension-header">JATK</label>' +
      '<label class="project-change-table-dimension-header">TIETY</label>' +
      '<label class="project-change-table-dimension-header">ELY</label>' +
      '<label class="project-change-table-dimension-header target">KÄÄNTÖ</label>' +
      '<label class="project-change-table-dimension-header">TIE</label>' +
      '<label class="project-change-table-dimension-header">AJR</label>' +
      '<label class="project-change-table-dimension-header">OSA</label>' +
      '<label class="project-change-table-dimension-header">AET</label>' +
      '<label class="project-change-table-dimension-header">LET</label>' +
      '<label class="project-change-table-dimension-header">PIT</label>' +
      '<label class="project-change-table-dimension-header">JATK</label>' +
      '<label class="project-change-table-dimension-header">TIETY</label>' +
      '<label class="project-change-table-dimension-header">ELY</label>');

    changeTableHeader.append('<div class="change-table-dimension-headers" style="overflow-y: auto;">' +
      '<table class="change-table-dimensions">' +
      '</table>' +
      '</div>');
    changeTable.append(changeTableHeader);

    function show() {
      $('.container').append(changeTable.toggle());
      resetInteractions();
      interact('.change-table-frame').unset();
      bindEvents();
      getChanges();
      setTableHeight();
      enableTableInteractions();
    }

    function hide() {
      $('#information-content').empty();
      $('#send-button').attr('disabled', true);
      resetInteractions();
      interact('.change-table-frame').unset();
      $('.change-table-frame').remove();
    }

    function resetInteractions() {
      var dragTable = $('.change-table-frame');
      if (dragTable && dragTable.length > 0) {
        dragTable[0].setAttribute('data-x', 0);
        dragTable[0].setAttribute('data-y', 0);
        dragTable.css('transform', 'none');
      }
    }

    function getChangeType(type){
      return changeTypes[type];
    }

    function getChanges() {
      var currentProject = projectCollection.getCurrentProject();
      projectChangeInfoModel.getChanges(currentProject.project.id);
    }

    function setTableHeight() {
      var changeTableHeight = parseInt(changeTable.height());
      var headerHeight = parseInt($('.change-table-header').height()) + parseInt($('.change-table-sections').height()) + parseInt($('.change-header').height());
      $('.change-table-dimension-headers').height(changeTableHeight - headerHeight - 30);// scroll size = total - header - border
    }

    function bindEvents(){
      $('.row-changes').remove();
      eventbus.once('projectChanges:fetched', function(projectChangeData) {
        var htmlTable = "";
        if (!_.isUndefined(projectChangeData) && projectChangeData !== null && !_.isUndefined(projectChangeData.changeTable) && projectChangeData.changeTable !== null) {
          _.each(projectChangeData.changeTable.changeInfoSeq, function (changeInfoSeq, index) {
            var rowColorClass = '';
            if (index % 2 !== 1) {
              rowColorClass = 'white-row';
            }
            htmlTable += '<tr class="row-changes ' + rowColorClass + '">';
            if (changeInfoSeq.changetype === LinkStatus.New.value) {
              htmlTable += getEmptySource(changeInfoSeq);
            } else {
              htmlTable += getSourceInfo(changeInfoSeq);
            }
            htmlTable += getReversed(changeInfoSeq);
            if (changeInfoSeq.changetype === LinkStatus.Terminated.value) {
              htmlTable += getEmptyTarget();
            } else {
              htmlTable += getTargetInfo(changeInfoSeq);
            }
            htmlTable += '</tr>';
          });
          setTableHeight();
        }
        $('.row-changes').remove();
        $('.change-table-dimensions').append($(htmlTable));
        if (projectChangeData.validationErrors.length === 0) {
          $('.change-table-header').html($('<div class="font-resize">Validointi ok. Alla näet muutokset projektissa.</div>'));
          var currentProject = projectCollection.getCurrentProject();
          if ($('.change-table-frame').css('display') === "block" && (currentProject.project.statusCode === ProjectStatus.Incomplete.value || currentProject.project.statusCode === ProjectStatus.ErrorInTR.value)) {
            $('#send-button').attr('disabled', false); //enables send button if changetable is open
          }
        } else {
          $('.change-table-header').html($('<div class="font-resize" style="color: rgb(255, 255, 0)">Tarkista validointitulokset. Yhteenvetotaulukko voi olla puutteellinen.</div>'));
        }
      });

      changeTable.on('click', 'button.max', function (){
        resetInteractions();
        $('.font-resize').css('font-size', '18px');
        if(windowMaximized) {
          $('.change-table-frame').height('260px');
          $('.change-table-frame').width('1135px');
          $('.change-table-frame').css('top', '620px');
          $('[id=change-table-borders-target]').height('210px');
          $('[id=change-table-borders-source]').height('210px');
          $('[id=change-table-borders-reversed]').height('210px');
          $('[id=change-table-borders-changetype]').height('210px');
          $('[id=buttonText]').text("Suurenna ");
          $('[id=sizeSymbol]').text("□");
          windowMaximized=false;
        } else {
          $('.change-table-frame').height('800px');
          $('.change-table-frame').width('1135px');
          $('.change-table-frame').css('top', '50px');
          $('[id=change-table-borders-target]').height('740px');
          $('[id=change-table-borders-source]').height('750px');
          $('[id=change-table-borders-reversed]').height('750px');
          $('[id=change-table-borders-changetype]').height('750px');
          $('[id=buttonText]').text("Pienennä ");
          $('[id=sizeSymbol]').text("_");
          windowMaximized=true;
        }
        setTableHeight();
      });

      changeTable.on('click', 'button.close', function (){
        hide();
      });
    }

    function getReversed(changeInfoSeq){
      return ((changeInfoSeq.reversed) ? '<td class="project-change-table-dimension">&#9745</td>': '<td class="project-change-table-dimension">&#9744</td>');
    }

    function getEmptySource(changeInfoSeq) {
      return '<td class="project-change-table-dimension-first">' + getChangeType(changeInfoSeq.changetype) + '</td>' +
        '<td class="project-change-table-dimension"></td>' +
        '<td class="project-change-table-dimension"></td>' +
        '<td class="project-change-table-dimension"></td>' +
        '<td class="project-change-table-dimension"></td>' +
        '<td class="project-change-table-dimension"></td>' +
        '<td class="project-change-table-dimension"></td>' +
        '<td class="project-change-table-dimension"></td>' +
        '<td class="project-change-table-dimension"></td>' +
        '<td class="project-change-table-dimension"></td>';
    }
    function getEmptyTarget() {
      return '<td class="project-change-table-dimension"></td>' +
        '<td class="project-change-table-dimension"></td>' +
        '<td class="project-change-table-dimension"></td>' +
        '<td class="project-change-table-dimension"></td>' +
        '<td class="project-change-table-dimension"></td>' +
        '<td class="project-change-table-dimension"></td>' +
        '<td class="project-change-table-dimension"></td>' +
        '<td class="project-change-table-dimension"></td>' +
        '<td class="project-change-table-dimension"></td>';
    }

    function getTargetInfo(changeInfoSeq){
      return '<td class="project-change-table-dimension">' + changeInfoSeq.target.roadNumber + '</td>'+
        '<td class="project-change-table-dimension">' + changeInfoSeq.target.trackCode + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.target.startRoadPartNumber + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.target.startAddressM + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.target.endAddressM + '</td>' +
        '<td class="project-change-table-dimension">' + (changeInfoSeq.target.endAddressM - changeInfoSeq.target.startAddressM) + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.target.discontinuity + '</td>' +
        '<td class="project-change-table-dimension">'+ changeInfoSeq.target.roadType + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.target.ely + '</td>';
    }

    function getSourceInfo(changeInfoSeq){
      return '<td class="project-change-table-dimension-first">' + getChangeType(changeInfoSeq.changetype) + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.source.roadNumber + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.source.trackCode + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.source.startRoadPartNumber + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.source.startAddressM + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.source.endAddressM + '</td>' +
        '<td class="project-change-table-dimension">' + (changeInfoSeq.source.endAddressM - changeInfoSeq.source.startAddressM) + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.source.discontinuity + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.source.roadType + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.source.ely + '</td>';
    }

    function dragListener (event) {
      var target = event.target,
        x = (parseFloat(target.getAttribute('data-x')) || 0) + event.dx,
        y = (parseFloat(target.getAttribute('data-y')) || 0) + event.dy;
      target.style.webkitTransform =
        target.style.transform =
          'translate(' + x + 'px, ' + y + 'px)';
      target.setAttribute('data-x', x);
      target.setAttribute('data-y', y);
    }


    function enableTableInteractions() {
      interact('.change-table-frame')
        .draggable({
          allowFrom: '.change-table-header',
          onmove: dragListener,
          restrict: {
            restriction: '.container',
            elementRect: { top: 0, left: 0, bottom: 1, right: 1 }
          }
        })
        .resizable({
          edges: { left: true, right: true, bottom: true, top: true },
          restrictEdges: {
            outer: '.container',
            endOnly: true
          },
          restrictSize: {
            min: { width: 650, height: 300 }
          },
          inertia: true
        })
        .on('resizemove', function (event) {
          var target = event.target,
            x = (parseFloat(target.getAttribute('data-x')) || 0),
            y = (parseFloat(target.getAttribute('data-y')) || 0);
          target.style.width  = event.rect.width + 'px';
          target.style.height = event.rect.height + 'px';
          x += event.deltaRect.left;
          y += event.deltaRect.top;
          target.style.webkitTransform = target.style.transform =
            'translate(' + x + 'px,' + y + 'px)';
          target.setAttribute('data-x', x);
          target.setAttribute('data-y', y);
          var fontResizeElements = $('.font-resize');
          var newFontSize =18*parseInt(target.style.width) / 950 + 'px';
          fontResizeElements.css('font-size', newFontSize);
          $('[id=change-table-borders-target]').height(parseFloat(target.style.height) - 50 + 'px');
          $('[id=change-table-borders-source]').height(parseFloat(target.style.height) - 50 + 'px');
          $('[id=change-table-borders-reversed]').height(parseFloat(target.style.height) - 50 + 'px');
          $('[id=change-table-borders-changetype]').height(parseFloat(target.style.height) - 50 + 'px');
          setTableHeight();
        });
    }

    eventbus.on('projectChangeTable:refresh', function() {
      bindEvents();
      getChanges();
      enableTableInteractions();
    });

    eventbus.on('projectChangeTable:hide', function() {
      hide();
    });

    return{
      show: show,
      hide: hide,
      bindEvents: bindEvents
    };
  };
})(this);
