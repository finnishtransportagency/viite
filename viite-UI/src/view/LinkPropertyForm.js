(function (root) {
    root.LinkPropertyForm = function (selectedLinkProperty, roadNamingTool) {
    var compactForm = false;
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
        attributes: [
          {value: 1, description: "Uusimaa"},
          {value: 2, description: "Varsinais-Suomi"},
          {value: 3, description: "Kaakkois-Suomi"},
          {value: 4, description: "Pirkanmaa"},
          {value: 8, description: "Pohjois-Savo"},
          {value: 9, description: "Keski-Suomi"},
          {value: 10, description: "Etelä-Pohjanmaa"},
          {value: 12, description: "Pohjois-Pohjanmaa"},
          {value: 14, description: "Lappi"}
        ]
      },
      {
        id: 'TIETYYPPI',
        attributes: [
          {value: 1, description: "Maantie"},
          {value: 2, description: "Lauttaväylä maantiellä"},
          {value: 3, description: "Kunnan katuosuus"},
          {value: 4, description: "Maantien työmaa"},
          {value: 5, description: "Yksityistie"},
          {value: 9, description: "Omistaja selvittämättä"},
          {value: 99, description: "Ei määritetty"}
        ]
      },
      {
        id: 'JATKUVUUS',
        attributes: [
          {value: 1, description: "Tien loppu"},
          {value: 2, description: "Epäjatkuva"},
          {value: 3, description: "ELY:n raja"},
          {value: 4, description: "Lievä epäjatkuvuus"},
          {value: 5, description: "Jatkuva"}
        ]
      }
    ];

    var roadTypeDynamicField = function(){
      var floatingTransfer = (!applicationModel.isReadOnly() && compactForm);
      var field = '';
      var uniqRoadTypes = _.uniq(_.pluck(selectedLinkProperty.get(), 'roadTypeId'));
      var decodedRoadTypes = "";
      _.each(uniqRoadTypes, function(rt) {
          if (decodedRoadTypes.length === 0) {
              decodedRoadTypes = rt + " " + decodeAttributes('TIETYYPPI', rt);
          } else {
              decodedRoadTypes = decodedRoadTypes + ", " + rt + " " + decodeAttributes('TIETYYPPI', rt);
          }
      });

      if (floatingTransfer) {
        field = '<div class="form-group">' +
            '<label class="control-label-floating">TIETYYPPI</label>' +
            '<p class="form-control-static-floating">' + decodedRoadTypes + '</p>' +
            '</div>' ;
      } else {
        field = '<div class="form-group">' +
            '<label class="control-label">TIETYYPPI</label>' +
            '<p class="form-control-static">' + decodedRoadTypes + '</p>' +
            '</div>';
      }
      return field;
    };

    var measureDynamicField = function(labelText, measure){
      var floatingTransfer = (!applicationModel.isReadOnly() && compactForm);
      var field = '';
      var addressValue =  _.min(_.pluck(selectedLinkProperty.get(), measure));
      if (floatingTransfer) {
        field = '<div class="form-group">' +
          '<label class="control-label-floating">' + labelText + '</label>' +
          '<p class="form-control-static-floating">' + addressValue + '</p>' +
          '</div>' ;
      } else {
        field = '<div class="form-group">' +
          '<label class="control-label">' + labelText + '</label>' +
          '<p class="form-control-static">' + addressValue + '</p>' +
          '</div>';
      }
      return field;
    };

    var floatingListField = function (labelText) {
      return '<div class="form-group">' +
          '<label class="control-label-floating-list">' + labelText + '</label>' +
          '</div>' ;
    };

    var formFields = function (sources, targets) {
      if(!_.isUndefined(sources))
        sources = [].concat( sources );
      if(!_.isUndefined(targets))
        targets = [].concat( targets.filter(function (link){
          return link.id === 0;
        }));
      $('.control-label-floating-list').remove();
      var linkIds = [];
      var ids = [];
      var field = "";
      var linkCounter = 0;
      field = floatingListField('VALITUT LINKIT (IRTI GEOMETRIASTA OLEVAT):');
      _.each(sources, function(slp) {
        var divId = "VALITUTLINKIT" + linkCounter;
        var linkId = slp.linkId;
        var id = _.isUndefined(slp.id) ? -1: slp.id;
        if ((_.isUndefined(_.find(linkIds, function(link){return link === linkId;})) || _.isUndefined(_.find(ids, function(link){return link === id;}))) && !_.isUndefined(linkId) ) {
          field = field + '<div class="form-group" id=' +divId +'>' +
            '<label class="control-label-floating">' + 'LINK ID:' + '</label>' +
            '<p class="form-control-static-floating">' + linkId + '</p>' +
            '</div>' ;
          linkIds.push(linkId);
          ids.push(id);
          linkCounter = linkCounter + 1;
        }
      });
      field = field + floatingListField('VALITUT LINKIT (TUNTEMATTOMAT):');
      linkIds = [];
      ids = [];
      _.each(targets, function(target) {
          var divId = "VALITUTLINKIT" + linkCounter;
          var linkId = target.linkId;
          var id = _.isUndefined(target.id) ? -1: target.id;
          if (_.isUndefined(_.find(linkIds, function(link){return link === linkId;})) || _.isUndefined(_.find(ids, function(link){return link === id;}))) {
              field = field + '<div class="form-group" id=' +divId +'>' +
                  '<label class="control-label-floating">' + 'LINK ID:' + '</label>' +
                  '<p class="form-control-static-floating">' + linkId + '</p>' +
                  '</div>' ;
              linkIds.push(linkId);
              ids.push(id);
              linkCounter = linkCounter + 1;
          }
      });
      return field;
    };

    var additionalSource = function(linkId, marker) {
      return (!_.isUndefined(marker)) ? '' +
      '<div class = "form-group" id = "additionalSource">' +
      '<div style="display:inline-flex;justify-content:center;align-items:center;">' +
      '<label class="control-label-floating"> LINK ID:</label>' +
      '<span class="form-control-static-floating" style="display:inline-flex;width:auto;margin-right:5px">' + linkId + '</span>' +
      '<span class="marker">' + marker + '</span>' +
      '<button class="add-source btn btn-new" id="additionalSourceButton-' + linkId + '" value="' + linkId + '">Lisää kelluva tieosoite</button>' +
      '</div>' +
      '</div>' : '' +
      '<div class = "form-group" id = "additionalSource">' +
      '<div style="display:inline-flex;justify-content:center;align-items:center;">' +
      '<label class="control-label-floating"> LINK ID:</label>' +
      '<span class="form-control-static-floating" style="display:inline-flex;width:auto;margin-right:5px">' + linkId + '</span>' +
      '</div>' +
      '</div>';
    };

    var adjacentsTemplate = function(){
      return '' +
          '<div class="target-link-selection" id="adjacentsData">' +
          '<div class="form-group" id="adjacents">' +
          '<% if(!_.isEmpty(adjacentLinks)){ %>' +
          '<br><br><label class="control-label-adjacents">VALITTAVISSA OLEVAT TIELINKIT, JOILTA PUUTTUU TIEOSOITE:</label>' +
          ' <% } %>' +
          '<% _.forEach(adjacentLinks, function(l) { %>' +
          '<div style="display:inline-flex;justify-content:center;align-items:center;">' +
          '<label class="control-label-floating"> LINK ID: </label>' +
          '<span class="form-control-static-floating" style="display:inline-flex;width:auto;margin-right:5px"><%= l.linkId %></span>' +
          '<span class="marker"><%= l.marker %></span>' +
          '<button class="select-adjacent btn btn-new" id="sourceButton-<%= l.linkId %>" value="<%= l.linkId %>">Valitse</button>' +
          '</div>' +
          '</span>' +
          '</label>' +
          ' <% }) %>' +
          '</div>' +
          '</div>';
    };

    var afterCalculationTemplate = function () {
      return '' +
      '<div class="form-group" id="afterCalculationInfo">' +
      '<br><br>' +
      '<p><span style="margin-top:6px; color:#ffffff; padding-top:6px; padding-bottom:6px; line-height:15px;">TARKISTA TEKEMÄSI MUUTOKSET KARTTANÄKYMÄSTÄ.</span></p>' +
      '<p><span style="margin-top:6px; color:#ffffff; padding-top:6px; padding-bottom:6px; line-height:15px;">JOS TEKEMÄSI MUUTOKSET OVAT OK, PAINA TALLENNA</span></p>' +
      '<p><span style="margin-top:6px; color:#ffffff; padding-top:6px; padding-bottom:6px; line-height:15px;">JOS HALUAT KORJATA TEKEMÄSI MUUTOKSIA, PAINA PERUUTA</span></p>' +
      '</div>';
    };

    var decodeAttributes = function(attr, value) {
      var attrObj = _.find(decodedAttributes, function (obj) { return obj.id === attr; });
      if (!_.isUndefined(attrObj)) {
        var attrValue = _.find(attrObj.attributes, function (obj) { return obj.value === value; });
        if (!_.isUndefined(attrValue)) {
          return attrValue.description;
        } else {
          return "Ei määritetty";
        }
      } else {
        return "";
      }
    };

    var staticField = function(labelText, dataField) {
      var floatingTransfer = (!applicationModel.isReadOnly() && compactForm);
      var field;

      if (floatingTransfer) {
        field = '<div class="form-group">' +
          '<label class="control-label-floating">' + labelText + '</label>' +
          '<p class="form-control-static-floating">' + dataField + " " + decodeAttributes(labelText, dataField) + '</p>' +
          '</div>';
      } else {
        field = '<div class="form-group">' +
          '<label class="control-label">' + labelText + '</label>' +
          '<p class="form-control-static">' + dataField + " " + decodeAttributes(labelText, dataField) + '</p>' +
          '</div>';
      }
      return field;
    };

    var title = function() {
      return '<span>Tieosoitteen ominaisuustiedot</span>';
    };

    var editButtons =
      '<div class="link-properties form-controls">' +
      '<button class="continue ready btn btn-continue" disabled>Valinta valmis</button>'  +
      '<button class="calculate btn btn-move" disabled>Siirrä</button>' +
      '<button class="save btn btn-save" disabled>Tallenna</button>' +
      '<button class="cancel btn btn-cancel" disabled>Peruuta</button>' +
      '</div>';

    var notificationFloatingTransfer = function(displayNotification) {
      if (displayNotification) {
        return '' +
          '<div class="form-group form-notification">' +
          '<p>Tien geometria on muuttunut. Korjaa tieosoitesegmentin sijainti vastaamaan nykyistä geometriaa.</p>' +
          '</div>';
      } else {
        return '';
      }
    };

    var template = function(linkProperty) {
      var roadTypes = selectedLinkProperty.count() === 1 ? staticField('TIETYYPPI', linkProperty.roadTypeId) : roadTypeDynamicField();
      var startAddress = selectedLinkProperty.count() === 1 ? staticField('ALKUETÄISYYS', linkProperty.startAddressM) : measureDynamicField('ALKUETÄISYYS', 'startAddressM');
      var endAddress = selectedLinkProperty.count() === 1 ? staticField('LOPPUETÄISYYS', linkProperty.endAddressM) : measureDynamicField('LOPPUETÄISYYS', 'endAddressM');
      return _.template('' +
        '<header>' +
          title() +
        '</header>' +
        '<div class="wrapper read-only">' +
          '<div class="form form-horizontal form-dark">' +
            '<div class="form-group">' +
              '<p class="form-control-static asset-log-info">Muokattu viimeksi: <%- modifiedBy %> <%- modifiedAt %></p>' +
            '</div>' +
            '<div class="form-group">' +
              '<p class="form-control-static asset-log-info">Linkkien lukumäärä: ' + selectedLinkProperty.count() + '</p>' +
            '</div>' +
            staticField('TIENUMERO', linkProperty.roadNumber) +
            staticField('TIEOSANUMERO', linkProperty.roadPartNumber) +
            staticField('AJORATA', linkProperty.trackCode) +
            startAddress +
            endAddress +
            staticField('ELY', linkProperty.elyCode) +
            roadTypes +
            staticField('JATKUVUUS', linkProperty.discontinuity) +
          '</div>' +
        '</div>' +
        '<footer>' + '</footer>');
    };

    var templateFloating = function(linkProperty) {
      var startAddress = selectedLinkProperty.count() === 1 ? staticField('ALKUETÄISYYS', linkProperty.startAddressM) : measureDynamicField('ALKUETÄISYYS', 'startAddressM');
      var endAddress = selectedLinkProperty.count() === 1 ? staticField('LOPPUETÄISYYS', linkProperty.endAddressM) : measureDynamicField('LOPPUETÄISYYS', 'endAddressM');
      var roadTypes = selectedLinkProperty.count() === 1 ? staticField('TIETYYPPI', linkProperty.roadTypeId) : roadTypeDynamicField();
      return _.template('' +
        '<header>' +
          title() +
        '</header>' +
        '<div class="wrapper read-only-floating">' +
          '<div class="form form-horizontal form-dark">' +
            '<div class="form-group">' +
              '<p class="form-control-static asset-log-info">Muokattu viimeksi: <%- modifiedBy %> <%- modifiedAt %></p>' +
            '</div>' +
            '<div class="form-group">' +
              '<p class="form-control-static asset-log-info">Linkkien lukumäärä: ' + selectedLinkProperty.count() + '</p>' +
            '</div>' +
            staticField('TIENUMERO', linkProperty.roadNumber) +
            staticField('TIEOSANUMERO', linkProperty.roadPartNumber) +
            staticField('AJORATA', linkProperty.trackCode) +
            startAddress +
            endAddress +
            roadTypes +
            notificationFloatingTransfer(true) +
          '</div>' +
        '</div>' +
        '<footer>' + '</footer>');
    };

    var templateFloatingEditMode = function(linkProperty) {
      var startAddress = selectedLinkProperty.count() === 1 ? staticField('ALKUETÄISYYS', linkProperty.startAddressM) : measureDynamicField('ALKUETÄISYYS', 'startAddressM');
      var endAddress = selectedLinkProperty.count() === 1 ? staticField('LOPPUETÄISYYS', linkProperty.endAddressM) : measureDynamicField('LOPPUETÄISYYS', 'endAddressM');
      var roadTypes = selectedLinkProperty.count() === 1 ? staticField('TIETYYPPI', linkProperty.roadTypeId) : roadTypeDynamicField();
      return _.template('<div style="display: none" id="floatingEditModeForm">' +
        '<header>' +
          title() +
        '</header>' +
        '<div class="wrapper edit-mode-floating">' +
          '<div class="form form-horizontal form-dark">' +
            '<div class="form-group">' +
              '<p class="form-control-static asset-log-info">Muokattu viimeksi: <%- modifiedBy %> <%- modifiedAt %></p>' +
            '</div>' +
            '<div class="form-group">' +
              '<p class="form-control-static asset-log-info">Linkkien lukumäärä: ' + selectedLinkProperty.count() + '</p>' +
            '</div>' +
            staticField('TIENUMERO',linkProperty.roadNumber) +
            staticField('TIEOSANUMERO', linkProperty.roadPartNumber) +
            startAddress +
            endAddress +
            staticField('AJORATA', linkProperty.trackCode) +
            roadTypes +
            notificationFloatingTransfer(true) +
            formFields(selectedLinkProperty.getSources() ? selectedLinkProperty.getSources() : selectedLinkProperty.get()) +
          '</div>' +
        '</div>' +
        '<footer>' + editButtons + '</footer> </div>');
    };

    var processAdditionalFloatings = function(floatingRoads, value) {
      var floatingRoadsLinkId = _.map(floatingRoads, function (fr) {
        return fr.linkId;
      });
      if (_.contains(floatingRoadsLinkId, parseInt(value))) {
        var rootElement = $('#feature-attributes');
        var floatingToAdd = _.filter(floatingRoads, function(floating){
          return floating.linkId === parseInt(value);
        });

        applicationModel.addSpinner();
        eventbus.trigger("adjacents:additionalSourceSelected", floatingToAdd, value);
        rootElement.find('.link-properties button.continue').attr('disabled', false);
        rootElement.find('.link-properties button.cancel').attr('disabled', false);
        applicationModel.setActiveButtons(true);
      }
    };

    var addOpenProjectButton = function() {
      var rootElement = $('#feature-attributes');
      rootElement.empty();
        var emptyFormDiv =
            '<p class="center"><a id="floating-list-link" class="floating-stops" href="#work-list/floatingRoadAddress">KORJATTAVIEN LINKKIEN LISTA</a></p>' +
            '<p class="center"><a id="error-list-link" class="floating-stops" href="#work-list/roadAddressErrors">TIEOSOITEVERKON VIRHEET</a></p>' +
            '<p class="form form-horizontal"></p>' +
            '<div class="form-initial-state" id="emptyFormDiv">' +
              '<span class="header-noposition">Aloita valitsemalla projekti.</span>' +
              '<button id="formProjectButton" class="action-mode-btn btn btn-block btn-primary">Tieosoiteprojektit</button>' +
              '<button id="formNameToolButton" class="open-tool-mode-btn btn btn-block btn-primary" style="margin-top: 5px;">Tiennimen ylläpito</button>' +
            '</div>';
      rootElement.append(emptyFormDiv);
      $('[id=formProjectButton]').click(function() {
        $('[id=projectListButton]').click();
        return false;
      });
        $('[id=formNameToolButton]').click(function () {
            roadNamingTool.toggle();
            return false;
        });
    };


    var bindEvents = function() {
      var rootElement = $('#feature-attributes');

      addOpenProjectButton();

      var switchMode = function (readOnly) {
        toggleMode(readOnly);
        var uniqFeaturesToKeep = _.uniq(selectedLinkProperty.getFeaturesToKeep());
        var firstFloatingSelected = _.first(_.filter(uniqFeaturesToKeep,function (feature) {
          return feature.roadLinkType === LinkValues.RoadLinkType.FloatingRoadLinkType.value;
        }));
        //checks if previousSelected road was not unknown and current select road IS unknown
        var canStartTransfer = compactForm && !applicationModel.isReadOnly() && uniqFeaturesToKeep.length > 1 && uniqFeaturesToKeep[uniqFeaturesToKeep.length - 1].anomaly === LinkValues.Anomaly.NoAddressGiven.value && uniqFeaturesToKeep[uniqFeaturesToKeep.length - 2].anomaly !== LinkValues.Anomaly.NoAddressGiven.value;
        if (canStartTransfer)
          _.defer(function() {
            selectedLinkProperty.getLinkAdjacents(selectedLinkProperty.get()[0], firstFloatingSelected);
          });
      };

      var toggleMode = function(readOnly) {
        if (!applicationModel.isProjectOpen()) {
          rootElement.find('.editable .form-control-static').toggle(readOnly);
          rootElement.find('select').toggle(!readOnly);
          rootElement.find('.form-controls').toggle(!readOnly);
          var uniqFeaturesToKeep = _.uniq(selectedLinkProperty.getFeaturesToKeep());
          var lastFeatureToKeep = _.isUndefined(_.last(_.initial(uniqFeaturesToKeep))) ? _.last(uniqFeaturesToKeep) : _.last(_.initial(uniqFeaturesToKeep));
          var firstSelectedLinkProperty = _.first(selectedLinkProperty.get());
          if (!_.isEmpty(uniqFeaturesToKeep)) {
            if (readOnly) {
              if (lastFeatureToKeep.roadLinkType === LinkValues.RoadLinkType.FloatingRoadLinkType.value) {
                rootElement.html(templateFloating(firstSelectedLinkProperty)(firstSelectedLinkProperty));
              } else {
                rootElement.html(template(firstSelectedLinkProperty)(firstSelectedLinkProperty));
              }
            } else {
              if (lastFeatureToKeep.roadLinkType === LinkValues.RoadLinkType.FloatingRoadLinkType.value) {
                rootElement.html(templateFloatingEditMode(firstSelectedLinkProperty)(firstSelectedLinkProperty));
                if (applicationModel.getSelectionType() === 'floating' && firstSelectedLinkProperty.roadLinkType === LinkValues.RoadLinkType.FloatingRoadLinkType.value) {
                  selectedLinkProperty.getLinkFloatingAdjacents(_.last(selectedLinkProperty.get()), firstSelectedLinkProperty);
                }
                $('#floatingEditModeForm').show();
              } else { //check if the before selected was a floating link and if the next one is unknown
                if (uniqFeaturesToKeep.length > 1 && uniqFeaturesToKeep[uniqFeaturesToKeep.length - 1].anomaly === LinkValues.Anomaly.NoAddressGiven.value) {
                  rootElement.html(templateFloatingEditMode(firstSelectedLinkProperty)(firstSelectedLinkProperty));
                  $('#floatingEditModeForm').show();
                } else {
                  rootElement.html(template(firstSelectedLinkProperty)(firstSelectedLinkProperty));
                }
              }
            }
          } else if (!_.isEmpty(selectedLinkProperty.get())) {
            if (readOnly) {
              if (firstSelectedLinkProperty.roadLinkType === LinkValues.RoadLinkType.FloatingRoadLinkType.value) {
                rootElement.html(templateFloating(firstSelectedLinkProperty)(firstSelectedLinkProperty));
              } else {
                rootElement.html(template(firstSelectedLinkProperty)(firstSelectedLinkProperty));
              }
            } else {
              if (_.last(selectedLinkProperty.get()).roadLinkType === LinkValues.RoadLinkType.FloatingRoadLinkType.value) {
                applicationModel.toggleSelectionTypeFloating();
                rootElement.html(templateFloatingEditMode(firstSelectedLinkProperty)(firstSelectedLinkProperty));
                selectedLinkProperty.getLinkFloatingAdjacents(_.last(selectedLinkProperty.get()), firstSelectedLinkProperty);
                $('#floatingEditModeForm').show();
              } else {
                rootElement.html(template(firstSelectedLinkProperty)(firstSelectedLinkProperty));
              }
            }
          }
          rootElement.find('.form-controls').toggle(!readOnly);
          rootElement.find('.btn-move').prop("disabled", true);
          rootElement.find('.btn-continue').prop("disabled", false);
        }
      };

      eventbus.on('linkProperties:selected linkProperties:cancelled', function(linkProperties) {
        rootElement.empty();
        if (!_.isEmpty(selectedLinkProperty.get()) || !_.isEmpty(linkProperties)) {

          compactForm = !_.isEmpty(selectedLinkProperty.get()) && (selectedLinkProperty.get()[0].roadLinkType === LinkValues.RoadLinkType.FloatingRoadLinkType.value || selectedLinkProperty.getFeaturesToKeep().length >= 1);
          linkProperties.modifiedBy = linkProperties.modifiedBy || '-';
          linkProperties.modifiedAt = linkProperties.modifiedAt || '';
          linkProperties.roadNameFi = linkProperties.roadNameFi || '';
          linkProperties.roadAddress = linkProperties.roadAddress || '';
          linkProperties.roadNumber = linkProperties.roadNumber || '';
          if (linkProperties.roadNumber > 0) {
            linkProperties.roadPartNumber = linkProperties.roadPartNumber || '';
            linkProperties.startAddressM = linkProperties.startAddressM || '0';
            linkProperties.trackCode = isNaN(parseFloat(linkProperties.trackCode)) ? '' : parseFloat(linkProperties.trackCode);
          } else {
            linkProperties.roadPartNumber = '';
            linkProperties.trackCode = '';
            linkProperties.startAddressM = '';
          }
          linkProperties.elyCode = isNaN(parseFloat(linkProperties.elyCode)) ? '' : linkProperties.elyCode;
          linkProperties.endAddressM = linkProperties.endAddressM || '';
          linkProperties.discontinuity = linkProperties.discontinuity || '';
          linkProperties.roadType = linkProperties.roadType || '';
          linkProperties.roadLinkType = linkProperties.roadLinkType || '';
          switchMode(applicationModel.isReadOnly());
        }
      });

      eventbus.on('form:showPropertyForm', function () {
        addOpenProjectButton();
      });

      eventbus.on('adjacents:added', function(sources, targets) {
        processAdjacents(sources,targets);
        applicationModel.removeSpinner();
      });

      eventbus.on('adjacents:floatingAdded', function(sources, targets, additionalSourceLinkId) {
        $('[id^=additionalSource]').remove();
        $('#control-label-floating').remove();
        $('#adjacentsData').empty();
        $('[id^=VALITUTLINKIT]').remove();
        processFloatingAdjacents(sources, targets, additionalSourceLinkId);
        applicationModel.removeSpinner();
      });

      var processFloatingAdjacents = function (sources, targets, additionalSourceLinkId) {
        var adjacents = _.reject(targets, function(t) {
          return t.roadLinkType === LinkValues.RoadLinkType.FloatingRoadLinkType.value;
        });

        if (!_.isUndefined(additionalSourceLinkId)) {
          return $(".form-group[id^='VALITUTLINKIT']:last").append('<div style="display:inline-flex;justify-content:center;align-items:center;">' +
            '<label class="control-label-floating"> LINK ID:</label>' +
            '<span class="form-control-static-floating" style="display:inline-flex;width:auto;margin-right:5px">' + additionalSourceLinkId + '</span>' +
            '</div>');
        }

        $('[id^=VALITUTLINKIT]').remove();

        var nonFloatingFeatures = selectedLinkProperty.getFeaturesToKeep();

        var fields = formFields(nonFloatingFeatures);

        $('.form-group:last').after(fields);
        var lastLinkElement = $(".form-group[id^='VALITUTLINKIT']:last");
        if (lastLinkElement.length !== 0 && lastLinkElement[0].childNodes.length <= 2) {
          $('#floatingEditModeForm').show();
          $('[id*="sourceButton"]').click({"sources": sources, "adjacents": adjacents},function(event) {
            eventbus.trigger("adjacents:nextSelected", event.data.sources, event.data.adjacents, event.currentTarget.value);
          });
          rootElement.find('.link-properties button.calculate').attr('disabled', true);
          rootElement.find('.link-properties button.cancel').attr('disabled', false);
          rootElement.find('.link-properties button.continue').attr('disabled', true);
          applicationModel.setActiveButtons(true);
          $('[id*="additionalSourceButton"]').click(sources,function(event) {
            processAdditionalFloatings(sources, event.currentTarget.value);
          });
        }
      };

      var processAdjacents = function (sources, targets) {
          var adjacents = _.reject(targets, function(t) {
              return t.roadLinkType === LinkValues.RoadLinkType.FloatingRoadLinkType.value;
          });

          $('[id^=VALITUTLINKIT]').remove();

          var nonFloatingFeatures = selectedLinkProperty.getFeaturesToKeep();
          var sourcesToShow = nonFloatingFeatures.filter(function (link) {
              return link.id !== 0;
          });
          var targetsToShow = nonFloatingFeatures.filter(function (link) {
              return link.id === 0;
          });
          selectedLinkProperty.setSources(sourcesToShow);
          var fields = formFields(sourcesToShow, targetsToShow);

          var fullTemplate = adjacentsTemplate();

          $('.form-group:last').after(fields);
          var lastLinkElement = $(".form-group[id^='VALITUTLINKIT']:last");
          if (lastLinkElement.length !== 0 && lastLinkElement[0].childNodes.length <= 2) {
              lastLinkElement.append($(_.template(fullTemplate)(_.merge({}, {"adjacentLinks": adjacents}))));
              $('#floatingEditModeForm').show();
              $('[id*="sourceButton"]').click({"sources": sources, "adjacents": adjacents},function(event) {
                  eventbus.trigger("adjacents:nextSelected", event.data.sources, event.data.adjacents, event.currentTarget.value);
              });
              rootElement.find('.link-properties button.calculate').attr('disabled', false);
              rootElement.find('.link-properties button.cancel').attr('disabled', false);
              rootElement.find('.link-properties button.continue').attr('disabled', true);
              applicationModel.setActiveButtons(true);
              $('[id*="additionalSourceButton"]').click(sources,function(event) {
                  processAdditionalFloatings(sources, event.currentTarget.value);
              });
          }
      };

      eventbus.on('linkProperties:changed', function() {
        rootElement.find('.link-properties button').attr('disabled', false);
      });

      eventbus.on('layer:selected', function(layer, previouslySelectedLayer, toggleStart) {
        if (layer === "linkProperty" && toggleStart) {
          addOpenProjectButton();
        }
      });

      eventbus.on('roadLayer:toggleProjectSelectionInForm', function(layer, noSave) {
        if (layer === "linkProperty") {
          addOpenProjectButton();
          if (noSave) {
            $('#formProjectButton').click();
          } else {
            eventbus.once('roadAddress:projectSaved', function() {
              $('#formProjectButton').click();
            });
          }
        }
      });

      eventbus.on('linkProperties:unselected', function() {
        if (('all' === applicationModel.getSelectionType() || 'floating' === applicationModel.getSelectionType()) && !applicationModel.isProjectOpen()) {
          addOpenProjectButton();
        }
      });

      eventbus.on('application:readOnly', toggleMode);
      rootElement.on('click', '.link-properties button.save', function() {
        if (applicationModel.getCurrentAction() === applicationModel.actionCalculated) {
          selectedLinkProperty.saveTransfer();
          applicationModel.setCurrentAction(-1);
          applicationModel.addSpinner();
        }
      });

      rootElement.on('click', '.link-properties button.cancel', function() {
        var action;
        if (applicationModel.isActiveButtons()) {
          action = applicationModel.actionCalculating;
        }
        applicationModel.setCurrentAction(action);
        eventbus.trigger('linkProperties:activateAllSelections');
        eventbus.trigger('roadLinks:refreshView');
        if ('all' === applicationModel.getSelectionType() || 'floating' === applicationModel.getSelectionType()) {
          selectedLinkProperty.clearAndReset(false);
          applicationModel.toggleSelectionTypeAll();
          applicationModel.addSpinner();
          eventbus.trigger('linkProperties:closed');
          selectedLinkProperty.close();
        } else {
          applicationModel.toggleSelectionTypeFloating();
          selectedLinkProperty.cancelAndReselect(action);
        }
        applicationModel.setActiveButtons(false);
      });
      rootElement.on('click', '.link-properties button.calculate', function() {
        applicationModel.addSpinner();
        selectedLinkProperty.transferringCalculation();
        applicationModel.setActiveButtons(true);
      });
      rootElement.on('click', '.link-properties button.continue',function() {
        if (selectedLinkProperty.continueSelectUnknown()) {
            eventbus.once('linkProperties:unknownsTreated', function () {
                rootElement.find('.link-properties button.continue').attr('disabled', true);
                eventbus.trigger('linkProperties:deselectFeaturesSelected');
                applicationModel.toggleSelectionTypeUnknown();
                applicationModel.setContinueButton(false);
                eventbus.trigger('linkProperties:highlightSelectedFloatingFeatures');
                eventbus.trigger('linkProperties:activateInteractions');
                eventbus.trigger('linkProperties:deactivateDoubleClick');
            });
            eventbus.trigger('linkProperties:drawUnknowns');
        }
      });

      eventbus.on('adjacents:roadTransfer', function(result, sourceIds, targets) {
        $('#additionalSource').remove();
        $('#control-label-floating').remove();
        $('#adjacentsData').empty();
        rootElement.find('.link-properties button.save').attr('disabled', false);
        rootElement.find('.link-properties button.cancel').attr('disabled', false);
        rootElement.find('.link-properties button.calculate').attr('disabled', true);
        rootElement.find('.link-properties button.continue').attr('disabled', true);
        $('[id^=VALITUTLINKIT]').remove();

        var fields = formFields(result, targets) + '' + afterCalculationTemplate();

        $('.form-group:last').after(fields);

        applicationModel.removeSpinner();
      });

      eventbus.on('adjacents:startedFloatingTransfer', function() {
        action = applicationModel.actionCalculating;
        rootElement.find('.link-properties button.cancel').attr('disabled', false);
        if (!applicationModel.isContinueButton()) {
          rootElement.find('.link-properties button.continue').attr('disabled', true);
        } else {
          rootElement.find('.link-properties button.continue').attr('disabled', false);
        }
        applicationModel.setActiveButtons(true);
        eventbus.trigger('layer:enableButtons', false);
      });

      eventbus.on('adjacents:floatingAdded', function(floatingRoads) {
        var floatingPart = '<br><label id="control-label-floating" class="control-label-floating">VIERESSÄ KELLUVIA TIEOSOITTEITA:</label>';
        _.each(floatingRoads,function(fr) {
          floatingPart = floatingPart + additionalSource(fr.linkId, fr.marker);
        });
        if (floatingRoads.length === 0) {
          applicationModel.setContinueButton(true);
          rootElement.find('.link-properties button.continue').attr('disabled', false);
        }
        else{
          $(".form-group:last").after(floatingPart);
          $('[id*="additionalSourceButton"]').click(floatingRoads,function(event) {
              processAdditionalFloatings(floatingRoads,event.currentTarget.value);
          });
        }
      });
      eventbus.on('linkProperties:additionalFloatingSelected',function(data) {
        processAdditionalFloatings(data.selectedFloatings, data.selectedLinkId);
      });

      eventbus.on('linkProperties:transferFailed',function(errorCode) {
        if (errorCode === 400) {
          return new ModalConfirm("Valittujen lähdelinkkien geometriaa ei saatu sovitettua kohdegeometrialle. Ota yhteyttä järjestelmätukeen.");
        } else if (errorCode === 401) {
          return new ModalConfirm("Sinulla ei ole käyttöoikeutta muutoksen tekemiseen.");
        } else if (errorCode === 412) {
          return new ModalConfirm("Täyttämättömien vaatimusten takia siirtoa ei saatu tehtyä. Ota yhteyttä järjestelmätukeen.");
        } else if (errorCode === 500) {
          return new ModalConfirm("Siirto ei onnistunut taustajärjestelmässä tapahtuneen virheen takia, ota yhteyttä järjestelmätukeen.");
        } else {
          return new ModalConfirm("Siirto ei onnistunut taustajärjestelmässä tapahtuneen tuntemattoman virheen takia, ota yhteyttä järjestelmätukeen.");
        }
      });

      eventbus.on('roadAddressProject:selected', function() {
        $('.wrapper').remove();
      });
    };
    bindEvents();
  };
})(this);
