// Contains the HTML templates for the project form
(function (root) {
  root.ProjectFormTemplates = function () {
    const discontinuityColumnWidth = '80px !important';

    const addSmallLabel = function (label, id, customWidth) {
      const idAttribute = id ? ` id="${id}"` : '';
      const styleAttribute = customWidth ? ` style="width: ${customWidth}"` : '';
      return `<label class="control-label-small"${idAttribute}${styleAttribute}>${label}</label>`;
    };

    const staticField = function (labelText, dataField) {
      return `
        <div class="form-group">
          <p class="form-control-static asset-log-info">${labelText} : ${dataField}</p>
        </div>`;
    };

    const largeInputField = function (dataField) {
      return `
        <div class="form-group">
          <label class="control-label">LISÄTIEDOT</label>
          <textarea class="form-control large-input roadAddressProject" id="lisatiedot">${dataField === undefined || dataField === null ? "" : dataField}</textarea>
        </div>
      `;
    };

    const inputFieldRequired = function (labelText, id, placeholder, value, maxLength) {
      let lengthLimit = '';
      if (maxLength)
        lengthLimit = `maxlength="${maxLength}"`;
      return `
        <div class="form-group input-required">
          <label class="control-label required">${labelText}</label>
          <input autocomplete="off" type="text" class="form-control" id ="${id}"${lengthLimit} placeholder ="${placeholder}" value="${value}"/>
        </div>
      `;
    };

    const title = function (projectName) {
      const projectNameFixed = (projectName) ? projectName : "Uusi tieosoiteprojekti";
      return `<span class ="edit-mode-title">${projectNameFixed}</span>`;
    };

    const actionButtons = function (currentProject, ProjectStatus) {
      return `
        <div class="project-form form-controls" id="actionButtons">
          ${currentProject.statusCode === ProjectStatus.Incomplete.value ? `<span id="deleteProjectSpan" class="deleteSpan">POISTA PROJEKTI <i id="deleteProject_${currentProject.id}" class="fas fa-trash-alt" value="${currentProject.id}"></i></span>` : ''}
          <button id="generalNext" class="save btn btn-save" style="width:auto;">Jatka toimenpiteisiin</button>
          <button id="saveAndCancelDialogue" class="cancel btn btn-cancel">Poistu</button>
        </div>`;
    };

    const actionButtonsForSelectedLinks = function (btnPrefix, notDisabled) {
      return `
      <div class="${btnPrefix}form form-controls" id="actionButtons">
        <button id="saveButton" class="update btn btn-save" ${notDisabled ? '' : 'disabled'} style="width:auto;">Tallenna</button>
        <button id="cancelButton" class="cancelLink btn btn-cancel">Peruuta</button>
      </div>`;
    };

    const projectTemplate = function () {
      return _.template(`
        <header>${title()}</header>
        <div class="wrapper read-only">
          <div class="form form-horizontal form-dark">
            <div class="edit-control-group project-choice-group">
              <% if (isNewProject) { %>
                ${staticField('Lisätty järjestelmäan', '-')}
                ${staticField('Muokattu viimeksi', '-')}
              <% } else { %>
                ${staticField('Lisätty järjestelmään', '<%= project.createdBy %> <%= project.startDate %>')}
                ${staticField('Muokattu viimeksi', '<%= project.modifiedBy %> <%= project.dateModified %>')}
              <% } %>
              <div class="form-group editable form-editable-roadAddressProject">
                <form id="roadAddressProject" class="input-unit-combination form-group form-horizontal roadAddressProject">
                  ${inputFieldRequired('*Nimi', 'nimi', '', '<%= project.name %>', 32)}
                  ${inputFieldRequired('*Alkupvm', 'projectStartDate', 'pp.kk.vvvv', '<%= project.startDate %>', 10)}
                  <div class="form-check-date-notifications"> 
                    <p id="projectStartDate-validation-notification"> </p>
                  </div>
                  ${largeInputField('<%= project.additionalInfo %>')}
                  <div class="form-group">
                    <label class="control-label"></label>
                    ${addSmallLabel('TIE') + addSmallLabel('AOSA') + addSmallLabel('LOSA')}
                  </div>
                  <div class="form-group">
                    <label class="control-label">Tieosat</label>
                    ${addSmallInputNumber('tie', '', 5) + addSmallInputNumber('aosa', '', 3) + addSmallInputNumber('losa', '', 3) + addReserveButton()}
                  </div>
                </form>
              </div>
            </div>
            <div class="form-result">
              <label><%= isNewProject ? "PROJEKTIIN VALITUT TIEOSAT:" : "PROJEKTIIN VARATUT TIEOSAT:" %></label>
              <div>
                ${addSmallLabel('TIE', null, '30px !important') + addSmallLabel('OSA') + addSmallLabel('PITUUS') + addSmallLabel('JATKUU', null, discontinuityColumnWidth) + addSmallLabel('ELY') + addSmallLabel('ELINVOIMAKESKUS')}
              </div>
              <div id="reservedRoads">
                <%= reservedRoads %>
              </div>
            </div>
            <% if (!isNewProject) { %>
              </br>
              </br>
              <div class="form-result">
                <label>PROJEKTISSA MUODOSTETUT TIEOSAT:</label>
                <div>
                  ${addSmallLabel('TIE', null, '30px !important') + addSmallLabel('OSA') + addSmallLabel('PITUUS') + addSmallLabel('JATKUU', null, discontinuityColumnWidth) + addSmallLabel('ELY') + addSmallLabel('ELINVOIMAKESKUS')}
                </div>
                <div id="newReservedRoads">
                  <%= newReservedRoads %>
                </div>
              </div>
            <% } %>
          </div>
        </div>
        <footer><%= actionButtonsHtml %></footer>`);
    };

    const projectEditTemplate = function (project, formCommon, buttonsHtml) {
      return (
        `<header>${formCommon.titleWithEditingTool(project)}</header>
        <div class="wrapper read-only">
          <div class="form form-horizontal form-dark">
            <label class="highlighted">ALOITA VALITSEMALLA KOHDE KARTALTA.</label>
            <div class="form-group" id="project-errors"></div>
          </div>
        </div>
        <footer>
          <div class="project-form form-controls">
            ${buttonsHtml}
          </div>
        </footer>`
      );
    };

    const errorsList = function (projectCollection, formCommon) {
      if (!projectCollection || !projectCollection.getProjectErrors || !formCommon) {
        return '';
      }
      
      const projectErrors = projectCollection.getProjectErrors();
      if (projectErrors && projectErrors.length > 0) {
        return (
          `<label>TARKASTUSILMOITUKSET:</label>
          <div id="projectErrors">
            ${formCommon.getProjectErrors ? formCommon.getProjectErrors(projectErrors, projectCollection.getAll ? projectCollection.getAll() : [], projectCollection) : ''}
          </div>`
        );
      }
      else
        return '';
    };

    const addSmallInputNumber = function (id, value, maxCharacters, customStyle) {
      const inputComponent = new NumberInput({
        id: id,
        value: value,
        maxCharacters: maxCharacters,
        customStyle: customStyle
      });
      return inputComponent.render();
    };

    const addReserveButton = function () {
      return `<button class="btn btn-reserve" disabled>Varaa</button>`;
    };

    const reservedHtmlList = function (list, projectCollection, currentProject, ProjectStatus) {
      if (!list || !Array.isArray(list) || !projectCollection) {
        return '';
      }
      
      let text = '';
      let index = 0;
      _.each(list, function (line) {
        if (!_.isUndefined(line.currentLength)) {
          text += `<div class="form-reserved-roads-list">
              ${addSmallLabel(line.roadNumber || '', null, '30px !important')}
              ${addSmallLabel(line.roadPartNumber || '', 'reservedRoadPartNumber')}
              ${addSmallLabel((line.currentLength || ''), 'reservedRoadLength')}
              ${addSmallLabel(line.currentDiscontinuity || '', 'reservedDiscontinuity', discontinuityColumnWidth)}
              ${addSmallLabel((line.currentEly || '0'), 'reservedEly')}
              ${addSmallLabel((line.currentEvk || ''), 'reservedEvk')}
              ${deleteButton(index++, line.roadNumber, line.roadPartNumber, 'reservedList', currentProject, ProjectStatus)}
              </div>`;
        }
      });
      return text;
    };

    const formedHtmlList = function (list, projectCollection, currentProject, ProjectStatus) {
      if (!list || !Array.isArray(list) || !projectCollection) {
        return '';
      }
      
      let text = '';
      let index = 0;
      _.each(list, function (line) {
        if (!_.isUndefined(line.newLength)) {
          text += `<div class="form-reserved-roads-list">
              ${addSmallLabel(line.roadNumber || '', null, "30px !important")}
              ${addSmallLabel(line.roadPartNumber || '', 'reservedRoadPartNumber')}
              ${addSmallLabel((line.newLength || ''), 'reservedRoadLength')}
              ${addSmallLabel(line.newDiscontinuity || '', 'reservedDiscontinuity', discontinuityColumnWidth)}
              ${addSmallLabel((line.newEly || '0'), 'reservedEly')}
              ${addSmallLabel((line.newEvk || ''), 'reservedEvk')}
              ${deleteButton(index++, line.roadNumber, line.roadPartNumber, 'formedList', currentProject, ProjectStatus)}
              </div>`;
        }
      });
      return text;
    };

    const deleteButton = function (index, roadNumber, roadPartNumber, selector, currentProject, ProjectStatus) {
      const disabledInput = currentProject && 
        currentProject.project &&
        ProjectStatus &&
        (currentProject.project.statusCode === ProjectStatus.InUpdateQueue.value ||
          currentProject.project.statusCode === ProjectStatus.UpdatingToRoadNetwork.value);
      return `<i roadNumber="${roadNumber}" roadPartNumber="${roadPartNumber}" id="${index}" class="delete btn-delete ${selector} fas fa-trash-alt fa-lg"" ${disabledInput ? 'disabled' : ''}></i>`;
    };

    // Public API
    return {
      staticField,
      largeInputField,
      inputFieldRequired,
      title,
      actionButtons,
      actionButtonsForSelectedLinks,
      projectTemplate,
      projectEditTemplate,
      errorsList,
      addSmallLabel,
      addSmallInputNumber,
      addReserveButton,
      reservedHtmlList,
      formedHtmlList,
      deleteButton
    };
  };
}(this));
