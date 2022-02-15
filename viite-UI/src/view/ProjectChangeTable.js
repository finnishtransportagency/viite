(function (root) {
  root.ProjectChangeTable = function (projectChangeInfoModel, projectCollection) {

    var changeTypes = [
      'Käsittelemättä',
      'Ennallaan',
      'Uusi',
      'Siirto',
      'Numerointi',
      'Lakkautettu'
    ];
    // change table is not open in the beginning of the project
    var changeTableOpen = false;
    var LinkStatus = LinkValues.LinkStatus;
    var ProjectStatus = LinkValues.ProjectStatus;
    var windowMaximized = false;
    var formCommon = new FormCommon('');

    // checks if change table state is open
    var isChangeTableOpen = function () {
      return changeTableOpen;
    };

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
      '<label class="change-table-heading-label" id="label-source">Nykyosoite<i id="label-source-btn" class="btn-icon sort fas fa-sort"></i></label>' +
      '<label class="change-table-heading-label" id="label-reverse"></label>' +
      '<label class="change-table-heading-label" id="label-target">Uusi osoite<i id="label-target-btn" class="btn-icon sort fas fa-sort"></i></label>');
    changeTableHeader.append('<div class="change-header">' +
      '<label class="project-change-table-dimension-header">TIE</label>' +
      '<label class="project-change-table-dimension-header">AJR</label>' +
      '<label class="project-change-table-dimension-header">OSA</label>' +
      '<label class="project-change-table-dimension-header">AET</label>' +
      '<label class="project-change-table-dimension-header">LET</label>' +
      '<label class="project-change-table-dimension-header">PIT</label>' +
      '<label class="project-change-table-dimension-header">JATK</label>' +
      '<label class="project-change-table-dimension-header">HALL</label>' +
      '<label class="project-change-table-dimension-header">ELY</label>' +
      '<label class="project-change-table-dimension-header target">KÄÄNTÖ</label>' +
      '<label class="project-change-table-dimension-header">TIE</label>' +
      '<label class="project-change-table-dimension-header">AJR</label>' +
      '<label class="project-change-table-dimension-header">OSA</label>' +
      '<label class="project-change-table-dimension-header">AET</label>' +
      '<label class="project-change-table-dimension-header">LET</label>' +
      '<label class="project-change-table-dimension-header">PIT</label>' +
      '<label class="project-change-table-dimension-header">JATK</label>' +
      '<label class="project-change-table-dimension-header">HALL</label>' +
      '<label class="project-change-table-dimension-header">ELY</label>');

    changeTableHeader.append('<div class="change-table-dimension-headers" style="overflow-y: auto;">' +
      '<table class="change-table-dimensions">' +
      '</table>' +
      '</div>');
    changeTable.append(changeTableHeader);

    function show() {
      $('.container').append(changeTable);
      resetInteractions();
      interact('.change-table-frame').unset();
      bindEvents();
      getChanges();
      setTableHeight();
      enableTableInteractions();
    }

    function hide() {
      // set change table state
      changeTableOpen = false;
      // enable action dropdown, save and cancel buttons
      formCommon.enableFormInteractions();
      $('#information-content').empty();
      // disable send button and set title attribute
      $('#send-button').attr('disabled', true);
      $('#send-button').attr('title', 'Hyväksy yhteenvedon jälkeen');
      $('#recalculate-button').attr('title', 'Etäisyyslukemat on päivitetty');
      // enable changes button and remove title attribute from it
      $('#changes-button').attr('disabled', false);
      $('#changes-button').removeAttr('title');
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

    function getChangeType(type) {
      return changeTypes[type];
    }

    function getChanges() {
      var currentProject = projectCollection.getCurrentProject();
      projectChangeInfoModel.getChanges(currentProject.project.id, function () {
        var source = $('[id=label-source-btn]');
        var target = $('[id=label-target-btn]');
        if (source.hasClass('fa-sort-down') || source.hasClass('fa-sort-up')) {
          projectChangeInfoModel.sortChanges('source', source.attr('class').match('fa-sort-up'));
        } else if (target.hasClass('fa-sort-down') || target.hasClass('fa-sort-up')) {
          projectChangeInfoModel.sortChanges('target', target.attr('class').match('fa-sort-up'));
        }
      });
    }

    function setTableHeight() {
      var changeTableHeight = parseInt(changeTable.height());
      var headerHeight = parseInt($('.change-table-header').height()) + parseInt($('.change-table-sections').height()) + parseInt($('.change-header').height());
      $('.change-table-dimension-headers').height(changeTableHeight - headerHeight - 30);// scroll size = total - header - border
    }

    function showChangeTable(projectChangeData) {
      var htmlTable = "";
      var warningM = projectChangeData.warningMessage;
      if (!_.isUndefined(warningM))
        new ModalConfirm(warningM);
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
      // set change table state to open
      changeTableOpen = true;
      if (projectChangeData && !_.isUndefined(projectChangeData.changeTable)) {
        var projectDate = new Date(projectChangeData.changeTable.changeDate).toLocaleDateString();
        $('.change-table-header').html($('<div class="font-resize">Validointi ok. Alla näet muutokset projektissa.</div><div class="font-resize">Alkupäivämäärä: ' + projectDate + '</div>'));
        var currentProject = projectCollection.getCurrentProject();
        // disable recalculate button if changetable is open and set title attribute
        formCommon.setDisabledAndTitleAttributesById("recalculate-button", true, "Etäisyyslukemia ei voida päivittää yhteenvetotaulukon ollessa auki");
        // disable changes button if changetable is open and set title attribute
        formCommon.setDisabledAndTitleAttributesById("changes-button", true, "Yhteenvetotaulukko on jo auki");
        if ($('.change-table-frame').css('display') === "block" && (currentProject.project.statusCode === ProjectStatus.Incomplete.value)) {
          //enable send button if changetable is open and remove title attribute
          formCommon.setDisabledAndTitleAttributesById("send-button", false, "");
        }
      } else {
        $('.change-table-header').html($('<div class="font-resize" style="color: rgb(255, 255, 0)">Tarkista validointitulokset. Yhteenvetotaulukko voi olla puutteellinen.</div>'));
      }
    }

    function bindEvents() {
      $('.row-changes').remove();
      eventbus.on('projectChanges:fetched', function (projectChangeData) {
        showChangeTable(projectChangeData);
      });

      changeTable.on('click', 'button.max', function () {
        resetInteractions();
        $('.font-resize').css('font-size', '18px');
        if (windowMaximized) {
          $('.change-table-frame').height('260px');
          $('.change-table-frame').width('1135px');
          $('.change-table-frame').css('top', '620px');
          $('[id=change-table-borders-target]').height('210px');
          $('[id=change-table-borders-source]').height('210px');
          $('[id=change-table-borders-reversed]').height('210px');
          $('[id=change-table-borders-changetype]').height('210px');
          $('[id=buttonText]').text("Suurenna ");
          $('[id=sizeSymbol]').text("□");
          windowMaximized = false;
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
          windowMaximized = true;
        }
        setTableHeight();
      });

      changeTable.on('click', 'button.close', function () {
        hide();
      });

      changeTable.on('click', "i[id^='label-'][id$='-btn']", function (event) {
        sortChanges(event.target);
      });
    }

    function sortChanges(btn) {
      if ($(btn).hasClass('fa-sort-up') || $(btn).hasClass('fa-sort')) {
        $(btn).removeClass('fa-sort');
        $(btn).removeClass('fa-sort-up');
        $(btn).addClass('fa-sort-down');
      } else {
        $(btn).removeClass('fa-sort-down');
        $(btn).addClass('fa-sort-up');
      }

      var side = btn.id.match('-(.*)-')[1];
      var otherBtn = $('[id=label-' + (side === 'source' ? 'target' : 'source') + '-btn');
      otherBtn.removeClass('fa-sort-down');
      otherBtn.removeClass('fa-sort-up');
      otherBtn.addClass('fa-sort');

      var projectChanges = projectChangeInfoModel.sortChanges(side, btn.className.match('fa-sort-up'));
      eventbus.trigger('projectChanges:fetched', projectChanges);
    }

    function getReversed(changeInfoSeq) {
      return ((changeInfoSeq.reversed) ? '<td class="project-change-table-dimension">&#10004;</td>' : '<td class="project-change-table-dimension"></td>');
    }

    /**
     Convert administrativeClass number value to text value
          1   = Valtio
          2   = Kunta
          3   = Yksit.
     default  = Yksit.
     */
    function getAdministrativeClassText(administrativeClass) {
      let text;
      switch(administrativeClass) {
        case 1:
          text = "Valtio";
              break;
        case 2:
          text = "Kunta";
              break;
        case 3:
          text = "Yksit.";
              break;
        default:
          text = "Yksit.";
      }
      return text;
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

    function getTargetInfo(changeInfoSeq) {
      return '<td class="project-change-table-dimension">' + changeInfoSeq.target.roadNumber + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.target.trackCode + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.target.startRoadPartNumber + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.target.startAddressM + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.target.endAddressM + '</td>' +
        '<td class="project-change-table-dimension">' + (changeInfoSeq.target.endAddressM - changeInfoSeq.target.startAddressM) + '</td>' +
        '<td class="project-change-table-dimension">' + replaceParallelLink(changeInfoSeq.target.discontinuity) + '</td>' +
        '<td class="project-change-table-dimension">' + getAdministrativeClassText(changeInfoSeq.target.administrativeClass ) + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.target.ely + '</td>';
    }

    function getSourceInfo(changeInfoSeq) {
      return '<td class="project-change-table-dimension-first">' + getChangeType(changeInfoSeq.changetype) + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.source.roadNumber + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.source.trackCode + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.source.startRoadPartNumber + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.source.startAddressM + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.source.endAddressM + '</td>' +
        '<td class="project-change-table-dimension">' + (changeInfoSeq.source.endAddressM - changeInfoSeq.source.startAddressM) + '</td>' +
        '<td class="project-change-table-dimension">' + replaceParallelLink(changeInfoSeq.source.discontinuity) + '</td>' +
        '<td class="project-change-table-dimension">' + getAdministrativeClassText(changeInfoSeq.source.administrativeClass ) + '</td>' +
        '<td class="project-change-table-dimension">' + changeInfoSeq.source.ely + '</td>';
    }

    function dragListener(event) {
      var target = event.target,
        x = (parseFloat(target.getAttribute('data-x')) || 0) + event.dx,
        y = (parseFloat(target.getAttribute('data-y')) || 0) + event.dy;
      target.style.transform =
        'translate(' + x + 'px, ' + y + 'px)';
      target.style.webkitTransform = target.style.transform;
      target.setAttribute('data-x', x);
      target.setAttribute('data-y', y);
    }

    function replaceParallelLink(currentDiscontinuity) {
      if (currentDiscontinuity === LinkValues.Discontinuity.ParallelLink.value)
        return LinkValues.Discontinuity.Continuous.value;
      else
        return currentDiscontinuity;
    }

    function enableTableInteractions() {
      interact('.change-table-frame').draggable({
        allowFrom: '.change-table-header',
        onmove: dragListener,
        restrict: {
          restriction: '.container',
          elementRect: {top: 0, left: 0, bottom: 1, right: 1}
        }
      }).resizable({
        edges: {left: true, right: true, bottom: true, top: true},
        restrictEdges: {
          outer: '.container',
          endOnly: true
        },
        restrictSize: {
          min: {width: 650, height: 300}
        },
        inertia: true
      }).on('resizemove', function (event) {
        var target = event.target,
          x = (parseFloat(target.getAttribute('data-x')) || 0),
          y = (parseFloat(target.getAttribute('data-y')) || 0);
        target.style.width = event.rect.width + 'px';
        target.style.height = event.rect.height + 'px';
        x += event.deltaRect.left;
        y += event.deltaRect.top;
        target.style.transform =
          'translate(' + x + 'px,' + y + 'px)';
        target.style.webkitTransform = target.style.transform;
        target.setAttribute('data-x', x);
        target.setAttribute('data-y', y);
        var fontResizeElements = $('.font-resize');
        var newFontSize = (18 * parseInt(target.style.width) / 950) + 'px';
        fontResizeElements.css('font-size', newFontSize);
        $('[id=change-table-borders-target]').height(parseFloat(target.style.height) - 50 + 'px');
        $('[id=change-table-borders-source]').height(parseFloat(target.style.height) - 50 + 'px');
        $('[id=change-table-borders-reversed]').height(parseFloat(target.style.height) - 50 + 'px');
        $('[id=change-table-borders-changetype]').height(parseFloat(target.style.height) - 50 + 'px');
        setTableHeight();
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
      isChangeTableOpen: isChangeTableOpen
    };
  };
}(this));
