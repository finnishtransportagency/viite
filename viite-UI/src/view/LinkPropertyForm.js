(function (root) {
  root.LinkPropertyForm = function (selectedLinkProperty, roadNamingTool, projectListModel, roadAddressBrowser, roadAddressChangesBrowser, startupParameters, roadNetworkErrorsList, adminPanel) {
    var selectionType = ViiteEnumerations.SelectionType;

    // Helper function to convert ViiteEnumerations objects so they can be used here
    var createAttributesFromEnum = function(enumObj, useNameProperty) {
      return _.map(enumObj, function(item) {
        return {
          value: item.value,
          description: useNameProperty ? item.name : item.description
        };
      });
    };

    var decodedAttributes = [
      {
        id: 'AJORATA',
        attributes: [
          {value: 0, description: "Yksiajoratainen osuus"},
          {value: 1, description: "Oikeanpuoleinen ajorata"},
          {value: 2, description: "Vasemmanpuoleinen ajorata"}
        ]
      },
      {
        id: 'ELY',
        attributes: createAttributesFromEnum(ViiteEnumerations.ElyCodes, true)
      },
      {
        id: 'EVK',
        attributes: createAttributesFromEnum(ViiteEnumerations.EVKCodes, true)
      },
      {
        id: 'HALLINNOLLINEN LUOKKA',
        attributes: [
          {value: ViiteEnumerations.AdministrativeClass.PublicRoad.value, description: ViiteEnumerations.AdministrativeClass.PublicRoad.textValue},
          {value: ViiteEnumerations.AdministrativeClass.MunicipalityStreetRoad.value, description: ViiteEnumerations.AdministrativeClass.MunicipalityStreetRoad.textValue},
          {value: ViiteEnumerations.AdministrativeClass.PrivateRoad.value, description: ViiteEnumerations.AdministrativeClass.PrivateRoad.textValue},
          {value: ViiteEnumerations.AdministrativeClass.Unknown.value, description: ViiteEnumerations.AdministrativeClass.Unknown.description}
        ]
      },
      {
        id: 'JATKUVUUS',
        attributes: createAttributesFromEnum(ViiteEnumerations.Discontinuity, false).concat([
          {value: 6, description: "Rinnakkainen linkki"} /* 5. Jatkuva (Rinnakkainen linkki) */
        ])
      }
    ];

    /**
     * Used when more than 1 link is selected to extract all unique link property values and to form a template with said info
     *
     * @param attrId : id of the attribute to decode (e.g. 'JATKUVUUS')
     * @param linkProperty : property of the link (e.g. 'discontinuity')
     * @return html template with combined info of the selected links' link property that was specified
     * */
    var dynamicField = function (attrId, linkProperty) {
      var uniqLinkProperties = _.uniq(_.map(selectedLinkProperty.get(), linkProperty));
      var decodedLinkProperties = "";
      _.each(uniqLinkProperties, function(rt) {
        if (decodedLinkProperties.length === 0) {
          decodedLinkProperties = rt + " " + decodeAttributes(attrId, rt);
        } else {
          decodedLinkProperties = decodedLinkProperties + ', <br> ' + rt + ' ' + decodeAttributes(attrId, rt);
        }
      });
      return constructField(attrId, decodedLinkProperties);
    };

    var textDynamicField = function (labelText, linkProperty) {
      var uniqLinkProperties = _.uniq(_.map(selectedLinkProperty.get(), linkProperty));
      var linkProperties = "";
      _.each(uniqLinkProperties, function(rt) {
        if (rt !== undefined) {
          if (linkProperties.length === 0) {
            linkProperties = rt;
          } else {
            linkProperties = linkProperties + ', <br> ' + rt;
          }
        }
      });
      return constructField(labelText, linkProperties);
    };

    var lengthDynamicField = function () {
      var selectedLinks = selectedLinkProperty.get();
      var length = 0;
      var labelText = selectedLinks.length === 1 ? 'PITUUS' : 'YHTEENLASKETTU PITUUS';
      selectedLinks.forEach((link) => {
        var linkLength = link.addrMRange.end - link.addrMRange.start;
        length += linkLength;
      });
      return constructField(labelText, length);
    };

    var dateDynamicField = function () {
      function padTo2Digits(num) {
        return num.toString().padStart(2, '0');
      }

      function formatDate(date) {
        return [
          padTo2Digits(date.getDate()),
          padTo2Digits(date.getMonth() + 1),
          date.getFullYear()
        ].join('.');
      }

      var labelText = 'ALKUPÄIVÄMÄÄRÄ';
      var dates = [];
      var selectedLinks = selectedLinkProperty.get();
      selectedLinks.forEach((link) => {
        if (link.startDate.length > 0) {
          var dateParts = link.startDate.split(".");
          dates.push(new Date(dateParts[2], (dateParts[1] - 1), dateParts[0]));
        }
      });
      if (dates.length === 0) {
        return constructField(labelText, '');
      } else {
        var latestDate = new Date(Math.max.apply(null, dates));
        var formattedLatestDate = formatDate(latestDate);
        return constructField(labelText, formattedLatestDate);
      }
    };

    var constructField = function (labelText, data) {
      return '<div class="form-group">' +
          '<label class="control-label">' + labelText + '</label>' +
          '<p class="form-control-static">' + data + '</p>' +
          '</div>';
    };

    var decodeAttributes = function (attr, value) {
      var attrObj = _.find(decodedAttributes, function (obj) {
        return obj.id === attr;
      });
      if (attrObj) {
        var attrValue = _.find(attrObj.attributes, function (obj) {
          return obj.value === value;
        });
        if (attrValue) {
          return attrValue.description;
        } else {
          return "Ei määritelty";
        }
      } else {
        return "";
      }
    };

    var staticField = function (labelText, dataField) {
      return '<div class="form-group" style="margin-bottom: 0;">' +
          '<label class="control-label-short">' + labelText + '</label>' +
          '<p class="form-control-static-short">' + dataField + " " + decodeAttributes(labelText, dataField) + '</p>' +
          '</div>';
    };

    var title = function () {
      return '<span>Tieosoitteen ominaisuustiedot</span>';
    };

    var isOnlyOneRoadNumberSelected = function () {
      return _.uniq(_.map(selectedLinkProperty.get(), 'roadNumber')).length === 1;
    };

    var isOnlyOneRoadPartNumberSelected = function () {
      return _.uniq(_.map(selectedLinkProperty.get(), 'roadPartNumber')).length === 1;
    };

    var isOnlyOneRoadAndPartNumberSelected = function () {
      return isOnlyOneRoadNumberSelected() && isOnlyOneRoadPartNumberSelected();
    };

    var template = function (firstSelectedLinkProperty, linkProperties) {
      var mtkId = selectedLinkProperty.count() === 1 ? '; MTKID: ' + linkProperties.mmlId : '';
      var roadNames = selectedLinkProperty.count() === 1 ? staticField('TIEN NIMI', "roadName" in firstSelectedLinkProperty ? firstSelectedLinkProperty.roadName : '') : textDynamicField('TIEN NIMI', 'roadName');
      var roadNumbers = selectedLinkProperty.count() === 1 ? staticField('TIENUMERO', firstSelectedLinkProperty.roadNumber) : textDynamicField('TIENUMERO', 'roadNumber');
      var roadPartNumbers = isOnlyOneRoadNumberSelected() ? dynamicField('TIEOSANUMERO', 'roadPartNumber') : constructField('TIEOSANUMERO', '');
      var tracks = isOnlyOneRoadAndPartNumberSelected() ? dynamicField('AJORATA', 'trackCode') : constructField('AJORATA', '');
      var startAddress = isOnlyOneRoadAndPartNumberSelected() ? staticField('ALKUETÄISYYS', linkProperties.addrMRange.start) : constructField('ALKUETÄISYYS', '');
      var endAddress   = isOnlyOneRoadAndPartNumberSelected() ? staticField('LOPPUETÄISYYS', linkProperties.addrMRange.end) : constructField('LOPPUETÄISYYS', '');
      var combinedAddrLength = lengthDynamicField();
      var elys = selectedLinkProperty.count() === 1 ? staticField('ELY', firstSelectedLinkProperty.elyCode) : dynamicField('ELY', 'elyCode');
      var evks = selectedLinkProperty.count() === 1 ? staticField('EVK', firstSelectedLinkProperty.evkCode) : dynamicField('EVK', 'evkCode');
      var administrativeClasses = selectedLinkProperty.count() === 1 ? staticField('HALLINNOLLINEN LUOKKA', firstSelectedLinkProperty.administrativeClassId) : dynamicField('HALLINNOLLINEN LUOKKA', 'administrativeClassId');
      var discontinuities = isOnlyOneRoadAndPartNumberSelected() ? dynamicField('JATKUVUUS', 'discontinuity') : constructField('JATKUVUUS', '');
      var startDate = isOnlyOneRoadAndPartNumberSelected() ? dateDynamicField() : constructField('ALKUPÄIVÄMÄÄRÄ', '');
      return _.template('' +
        '<header>' +
        title() +
        '</header>' +
        '<div class="wrapper read-only">' +
        '<div class="form form-horizontal form-dark">' +
        '<div>' +
        '<div class="form-group-metadata">' +
        '<p class="form-control-static asset-log-info-metadata">Muokattu viimeksi: <%- modifiedBy %> <%- modifiedAt %></p>' +
        '</div>' +
        '<div class="form-group-metadata">' +
        '<p class="form-control-static asset-log-info-metadata">Linkkien lukumäärä: ' + selectedLinkProperty.count() + '</p>' +
        '</div>' +
        '<div class="form-group-metadata">' +
        '<p class="form-control-static asset-log-info-metadata">Geometrian lähde: ' + linkProperties.roadLinkSource + mtkId + '</p>' +
        '</div>' +
        showMunicipality() +
        showLinkId(selectedLinkProperty, linkProperties) +
        showLinkLength(selectedLinkProperty, linkProperties) +
        '</div>' +
        roadNames +
        roadNumbers +
        roadPartNumbers +
        tracks +
        startAddress +
        endAddress +
        combinedAddrLength +
        elys +
        evks +
        administrativeClasses +
        discontinuities +
        startDate +
        '</div>' +
        '</div>' +
        '<footer></footer>');
    };


    var showLinkId = function (selectedLinkPropertyToShow, linkProperties) {
      if (selectedLinkPropertyToShow.count() === 1) {
        return '' +
          '<div class="form-group-metadata">' +
          '<p class="form-control-static asset-log-info-metadata">Linkin ID: ' + linkProperties.linkId + '</p>' +
          '</div>';
      } else {
        return '';
      }
    };

    var showLinkLength = function (selectedLinkPropertyToShow, linkProperties) {
      if (selectedLinkPropertyToShow.count() === 1) {
        return '' +
            '<div class="form-group-metadata">' +
            '<p class="form-control-static asset-log-info-metadata">Geometrian pituus: ' + Math.round(linkProperties.endMValue - linkProperties.startMValue) + '</p>' +
            '</div>';
      } else {
        var roadLinks = selectedLinkPropertyToShow.get();
        var combinedLength = 0;
        _.map(roadLinks, function(roadLink){
          combinedLength += Math.round(roadLink.endMValue - roadLink.startMValue);
        });
        return '<div class="form-group-metadata">' +
            '<p class="form-control-static asset-log-info-metadata">Geometrioiden yhteenlaskettu pituus: ' + combinedLength + '</p>' +
            '</div>';
      }
    };

    var showMunicipality = function () {
      var municipalityValue = _.reduce(selectedLinkProperty.get(), function (acc, link) {
        return {
          municipalityName: acc.municipalityName,
          valid: acc.municipalityName === link.municipalityName
        };
      });
      if ((selectedLinkProperty.count() === 1 || municipalityValue.valid) && municipalityValue.municipalityName) {
        return '' +
          '<div class="form-group-metadata">' +
          '<p class="form-control-static asset-log-info-metadata">Kunta: ' + municipalityValue.municipalityName + '</p>' +
          '</div>';
      } else {
        return '';
      }
    };

    var addActionButtons = function () {
      var rootElement = $('#feature-attributes');
      rootElement.empty();

      var projectModeButton =  '<button id="formProjectButton" class="action-mode-btn btn btn-block btn-primary">Tieosoiteprojektit</button>';
      var roadNameToolButton = '<button id="formNameToolButton" class="open-tool-mode-btn btn btn-block btn-primary" style="margin-top: 5px;">Tiennimen ylläpito</button>';
      var nodesAndJunctionsButton = '<button id="formNodesAndJunctionsButton" class="open-tool-mode-btn btn btn-block btn-primary" style="margin-top: 5px;">Solmut ja liittymät</button>';
      var roadAddressBrowserButton = '<button id="formRoadAddressBrowserButton" class="open-tool-mode-btn btn btn-block btn-primary" style="margin-top: 5px;">Tieosoitteiden katselu</button>';
      var roadAddressChangesBrowserButton = '<button id="formRoadAddressChangesBrowserButton" class="open-tool-mode-btn btn btn-block btn-primary" style="margin-top: 5px;">Tieosoitemuutosten katselu</button>';
      var roadNetworkErrorsListButton = '<button id="formRoadNetworkErrorsListButton" class="open-tool-mode-btn btn btn-block btn-primary" style="margin-top: 5px;">Tieosoiteverkon virheet</button>';
      var adminPanelButton = '<button id="formAdminPanelButton" class="open-tool-mode-btn btn btn-block btn-primary" style="margin-top: 5px;">Admin paneeli</button>';


      var toolButtonsDivForViewMode =
          $('<div class="form-initial-state" id="emptyFormDiv">' +
              nodesAndJunctionsButton +
              roadAddressBrowserButton +
              roadAddressChangesBrowserButton +
          '</div>');

      var toolButtonsDivForWriteMode =
          $('<div class="form-initial-state" style>' +
              projectModeButton +
              roadNameToolButton +
              '</div>');

      var roadNetworkErrorsToolDiv = $('<div class="form-initial-state" style>' +
          roadNetworkErrorsListButton +
          '</div>');

      var adminPanelDiv = $('<div class="form-initial-state" style>' +
          adminPanelButton +
          '</div>');

      // add buttons for the view mode tools
      rootElement.append(toolButtonsDivForViewMode);

      // if the user has "viite" role then add the buttons for project mode and road name tool
      if (_.includes(startupParameters.roles, 'viite'))
        rootElement.prepend(toolButtonsDivForWriteMode);

      // if the user has "operator" role then add the button for road network error tool
      if (_.includes(startupParameters.roles, 'operator'))
        rootElement.append(roadNetworkErrorsToolDiv);

      // if the user has "admin" role then add the button for admin panel
      if (_.includes(startupParameters.roles, 'admin'))
        rootElement.append(adminPanelDiv);


      $('[id=formProjectButton]').click(function () {
        if (applicationModel.isProjectOpen()) {
          new ModalConfirm("Projektin muokkaus on kesken. Tallenna muutokset ja/tai poistu Peruuta-painikkeella.");
        } else {
          projectListModel.show();
        }
        return false;
      });

      $('[id=formNameToolButton]').click(function () {
        roadNamingTool.toggle();
        return false;
      });

      $('[id=formNodesAndJunctionsButton]').click(function () {
        eventbus.trigger('nodesAndJunctions:open');
        return false;
      });

      $('[id=formRoadAddressBrowserButton]').click(function () {
        roadAddressBrowser.toggle();
        return false;
      });

      $('[id=formRoadAddressChangesBrowserButton]').click(function () {
        roadAddressChangesBrowser.toggle();
        return false;
      });

      $('[id=formRoadNetworkErrorsListButton]').click(function () {
        roadNetworkErrorsList.showRoadNetworkErrorsListWindow();
        return false;
      });

      $('[id=formAdminPanelButton]').click(function () {
        adminPanel.showAdminPanelWindow();
        return false;
      });
    };

    var bindEvents = function () {
      var rootElement = $('#feature-attributes');

      addActionButtons();

      var toggleMode = function (readOnly, linkProperties) {
        if (!applicationModel.isProjectOpen()) {
          rootElement.find('.editable .form-control-static').toggle(readOnly);
          rootElement.find('select').toggle(!readOnly);
          rootElement.find('.form-controls').toggle(!readOnly);
          var firstSelectedLinkProperty = _.head(selectedLinkProperty.get());
          if (!_.isEmpty(selectedLinkProperty.get())) {
            rootElement.html(template(firstSelectedLinkProperty, linkProperties)(firstSelectedLinkProperty));
          }
          rootElement.find('.form-controls').toggle(!readOnly);
        }
      };

      eventbus.on('linkProperties:selected linkProperties:cancelled', function (linkProperties) {
        var props = _.cloneDeep(_.isArray(linkProperties) ? _.head(linkProperties) : linkProperties);
        rootElement.empty();
        if (!_.isEmpty(selectedLinkProperty.get()) || !_.isEmpty(props)) {

          props.startDate = props.startDate || '';
          props.modifiedBy = props.modifiedBy || '-';
          props.modifiedAt = props.modifiedAt || '';
          props.roadNameFi = props.roadNameFi || '';
          props.roadAddress = props.roadAddress || '';
          props.roadNumber = props.roadNumber || '';
          if (linkProperties.roadNumber > 0) {
            props.roadPartNumber = props.roadPartNumber || '';
            props.addrMRange.start = props.addrMRange.start || '0';
            props.trackCode = isNaN(parseFloat(props.trackCode)) ? '' : parseFloat(props.trackCode);
          } else {
            props.roadPartNumber = '';
            props.trackCode = '';
            props.addrMRange.start = '';
          }
          props.elyCode = isNaN(parseFloat(props.elyCode)) ? '' : props.elyCode;
          props.evkCode = isNaN(parseFloat(props.evkCode)) ? '' : props.evkCode;
          props.addrMRange.end = props.addrMRange.end || '';
          props.discontinuity = props.discontinuity || '';
          props.roadLinkType = props.roadLinkType || '';
          props.roadLinkSource = props.roadLinkSource || '';
          toggleMode(applicationModel.isReadOnly(), props);
        }
      });

      eventbus.on('form:showPropertyForm', function () {
        addActionButtons();
      });

      eventbus.on('linkProperties:changed', function () {
        rootElement.find('.link-properties button').attr('disabled', false);
      });

      eventbus.on('layer:selected', function (layer, previouslySelectedLayer, toggleStart) {
        if (layer === "linkProperty" && toggleStart) {
          addActionButtons();
        }
      });

      eventbus.on('roadLayer:toggleProjectSelectionInForm', function (layer, noSave) {
        if (layer === "linkProperty") {
          addActionButtons();
          if (noSave) {
            $('#formProjectButton').click();
          } else {
            eventbus.once('roadAddress:projectSaved', function () {
              $('#formProjectButton').click();
            });
          }
        }
      });

      eventbus.on('linkProperties:unselected', function () {
        if (applicationModel.selectionTypeIs(selectionType.All) && !applicationModel.isProjectOpen()) {
          addActionButtons();
        }
      });

      eventbus.on('roadAddressProject:selected', function () {
        $('.wrapper').remove();
      });

    };
    bindEvents();
  };
}(this));
