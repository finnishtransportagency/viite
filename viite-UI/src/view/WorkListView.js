(function (root) {
  var counter = 0;
  var elyDecoded = [
    {value: 1, description: "Uusimaa"},
    {value: 2, description: "Varsinais-Suomi"},
    {value: 3, description: "Kaakkois-Suomi"},
    {value: 4, description: "Pirkanmaa"},
    {value: 8, description: "Pohjois-Savo"},
    {value: 9, description: "Keski-Suomi"},
    {value: 10, description: "Etelä-Pohjanmaa"},
    {value: 12, description: "Pohjois-Pohjanmaa"},
    {value: 14, description: "Lappi"}
  ];
  var decodeEly = function (ely) {
    var elyObject = _.find(elyDecoded, function (obj) {
      return obj.value === Number(ely);
    });
    return ((elyObject) ? elyObject.description : "Unknown");
  };
  var floatingLinksTable = function (layerName, floatingLinks, elyCode) {
    counter += floatingLinks.length;
    var elyHeader = function (elyCodeHeader) {
      return $('<h2></h2>').html("ELY " + elyCodeHeader + " " + decodeEly(elyCodeHeader));
    };

    var tableContentRows = function (links) {
      return _.map(links, function (link) {
        return $('<tr></tr>').append($('<td align=left style="font-size: smaller;"></td>').append(floatingDescription('TIE', link.roadNumber)).append(floatingDescription('OSA', link.roadPartNumber)).append(floatingDescription('AJR', link.trackCode)).append(floatingDescription('AET', link.startAddressM)).append(floatingDescription('LET', link.endAddressM))).append($('<td align=right></td>').append(floatingLink(link)));
      });
    };
    var floatingLink = function (floating) {
      var link = '#' + layerName + '/' + floating.linkId;
      return $('<a style="font-size: smaller;" class="work-list-item"></a>').attr('href', link).html(link);
    };

    var floatingDescription = function (desc, value) {
      return $('<td align=left style="width: 100px;"> <b>' + desc + '</b>: ' + value + '</td>');
    };

    var tableToDisplayFloatings = function (tableFloatingLinks) {
      if (!tableFloatingLinks || tableFloatingLinks.length === 0) return '';
      return $('<table></table>').addClass('table').append(tableContentRows(tableFloatingLinks));
    };
    return $('<div></div>').append(elyHeader(elyCode)).append(tableToDisplayFloatings(floatingLinks));
  };

  var roadAddressErrorsTable = function (layerName, addressErrors, elyCode) {
    counter += addressErrors.length;
    var elyHeader = function (elyCodeHeader) {
      return $('<h2></h2>').html("ELY " + elyCodeHeader + " " + decodeEly(elyCodeHeader));
    };

    var tableContentRows = function (addresses) {
      return _.map(addresses, function (address) {
        return $('<tr></tr>').append($('<td align=left style="font-size: smaller;"></td>').append(errorsDescription('ID', address.id)).append(errorsDescription('TIE', address.roadNumber)).append(errorsDescription('OSA', address.roadPartNumber)).append(errorsDescription('ERROR', address.errorCode))).append($('<td align=right></td>').append(roadAddressError(address)));
      });
    };

    var roadAddressError = function (roadAddress) {
      var link = '#' + layerName + '/' + roadAddress.linkId;
      return $('<a style="font-size: smaller;" class="work-list-item"></a>').attr('href', link).html(link);
    };

    var errorsDescription = function (desc, value) {
      return $('<td align=left style="width: 100px;"> <b>' + desc + '</b>: ' + value + '</td>');
    };

    var tableToDisplayErrors = function (addressErrorsTable) {
      if (!addressErrorsTable || addressErrorsTable.length === 0) return '';
      return $('<table></table>').addClass('table').append(tableContentRows(addressErrorsTable));
    };
    return $('<div></div>').append(elyHeader(elyCode)).append(tableToDisplayErrors(addressErrors));
  };

  var generateWorkListFloatings = function (layerName, listP) {
    var title = {
      linkProperty: 'Korjattavien linkkien lista'
    };
    $('#work-list').append('' +
      '<div style="overflow: auto;">' +
      '<div class="page">' +
      '<div class="content-box">' +
      '<header>' + title[layerName] +
      '<a class="header-link" href="#' + layerName + '">Sulje lista</a>' +
      '</header>' +
      '<div class="work-list">' +
      '</div>' +
      '</div>' +
      '</div>'
    );
    var showApp = function () {
      $('.container').show();
      $('#work-list').hide();
      $('body').removeClass('scrollable').scrollTop(0);
      $(window).off('hashchange', showApp);
    };
    $(window).on('hashchange', showApp);

    listP.then(function (floatings) {
      counter = 0;
      var floatingLinks = _.map(floatings, _.partial(floatingLinksTable, layerName));
      if (counter === 0) {
        $('.work-list').html("").append($('<h3 style="padding-left: 10px;"></h3>').html("Kaikki irti geometriasta olevat tieosoitteet käsitelty"));
      } else {
        $('.work-list').html("").append($('<h3 style="padding-left: 10px;"></h3>').html(" " + counter + " tieosoitetta on irti geometriasta")).append(floatingLinks);
      }
      removeSpinner();
    });
  };

  var generateWorkListErrors = function (layerName, listP) {
    var title = {
      linkProperty: 'Tieosoiteverkon virheet'
    };
    $('#work-list').append('' +
      '<div style="overflow: auto;">' +
      '<div class="page">' +
      '<div class="content-box">' +
      '<header>' + title[layerName] +
      '<a class="header-link" href="#' + layerName + '">Sulje lista</a>' +
      '</header>' +
      '<div class="work-list">' +
      '</div>' +
      '</div>' +
      '</div>'
    );
    var showApp = function () {
      $('.container').show();
      $('#work-list').hide();
      $('body').removeClass('scrollable').scrollTop(0);
      $(window).off('hashchange', showApp);
    };
    $(window).on('hashchange', showApp);

    listP.then(function (errors) {
      counter = 0;
      var roadAddressErrors = _.map(errors, _.partial(roadAddressErrorsTable, layerName));
      if (counter === 0) {
        $('.work-list').html("").append($('<h3 style="padding-left: 10px;"></h3>').html("Kaikki irti geometriasta olevat tieosoitteet käsitelty"));
      } else {
        $('.work-list').html("").append($('<h3 style="padding-left: 10px;"></h3>').html(" " + counter + " addresses have errors")).append(roadAddressErrors);
      }
      removeSpinner();
    });
  };

  var addSpinner = function () {
    $('#work-list').append('<div class="spinner-overlay modal-overlay"><div class="spinner"></div></div>');
  };

  var removeSpinner = function () {
    jQuery('.spinner-overlay').remove();
  };

  var bindEvents = function () {
    eventbus.on('workList-floatings:select', function (layerName, listP) {
      $('#work-list').html("").show();
      addSpinner();
      $('.container').hide();
      $('body').addClass('scrollable');
      generateWorkListFloatings(layerName, listP);
    });

    eventbus.on('workList-errors:select', function (layerName, listP) {
      $('#work-list').html("").show();
      addSpinner();
      $('.container').hide();
      $('body').addClass('scrollable');
      generateWorkListErrors(layerName, listP);
    });
  };

  root.WorkListView = {
    initialize: function () {
      bindEvents();
    }
  };

}(this));
