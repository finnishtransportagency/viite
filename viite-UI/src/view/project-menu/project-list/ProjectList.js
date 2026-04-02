// Displays road address projects in a table format, allowing sorting, filtering and opening projects.
// Polls for project state updates every 30 seconds.
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { dateutil } from '@utils/DateUtils.js';
import { eventbus } from '@utils/eventbus.js';
import { eventutil } from '@utils/EventUtils.js';

export function ProjectList(projectCollection, options = {}) {
  const applicationApi = options.applicationApi;
  const applicationModel = options.applicationModel;
  const resolveProjectMenu = function () {
    if (_.isFunction(options.projectMenu)) {
      return options.projectMenu();
    }
    return options.projectMenu;
  };
    const projectStatus = ViiteEnumerations.ProjectStatus;

    const state = {
      projects: [],
      orderBy: { id: "sortStatus", reversed: false },
      filterBox: { input: "", visible: false },
      loading: false,
      onlyActive: true
    };

    let $container = $('<div id="project-list-root"></div>');
    let pollProjects = null;
    let modalContainer = null;

    // --- HEADERS CONFIGURATION ---
    const headers = {
      "sortName": { toStr: "PROJEKTIN NIMI", width: "180", sortFunc: (a, b) => a.name.localeCompare(b.name, 'fi') },
      "sortEVK": {
        toStr: "ELINVOIMAKESKUS", width: "170", sortFunc: (a, b) => {
          const aEvks = a.evks || [];
          const bEvks = b.evks || [];
          for (let i = 0; i < Math.min(aEvks.length, bEvks.length); i++) {
            if (aEvks[i] !== bEvks[i]) {
              return aEvks[i] - bEvks[i];
            }
          }
          return aEvks.length - bEvks.length;
        }
      },
      "sortUser": { toStr: "KÄYTTÄJÄ", width: "155", sortFunc: (a, b) => a.createdBy.localeCompare(b.createdBy, 'fi') },
      "sortDate": { toStr: "LUONTIPVM", width: "155", sortFunc: (a, b) => toComparableDate(b.createdDate) - toComparableDate(a.createdDate) },
      "sortStatus": { toStr: "TILA", width: "155", sortFunc: (a, b) => {
          const statusOrder = { [projectStatus.ErrorInViite.value]: 1, [projectStatus.InUpdateQueue.value]: 2, [projectStatus.UpdatingToRoadNetwork.value]: 3, [projectStatus.Incomplete.value]: 4, [projectStatus.Accepted.value]: 5, [projectStatus.Deleted.value]: 6, [projectStatus.Unknown.value]: 99 };
          const diff = (statusOrder[a.statusCode] || 99) - (statusOrder[b.statusCode] || 99);
          return diff !== 0 ? diff : toComparableDate(b.createdDate) - toComparableDate(a.createdDate);
        }
      }
    };

    // --- UTILITIES ---
    const toComparableDate = (dateStr) => {
      if (!dateStr) return new Date(0);
      return dateutil.isFinnishDateString(dateStr) ? dateutil.parseDate(dateStr) : new Date(dateStr);
    };

    const getSortIcon = (id) => {
      if (state.orderBy.id !== id) {
        return 'fa-sort';
      }
      return state.orderBy.reversed ? 'fa-sort-down' : 'fa-sort-up';
    };

    const staticField = (data) => `<div><label class="control-label-projects-list">${data || ''}</label></div>`;

    const columnClassById = {
      sortName: 'column-name',
      sortEVK: 'column-evk',
      sortUser: 'column-user',
      sortDate: 'column-date',
      sortStatus: 'column-status'
    };

    const columnGroupTemplate = () => `
      <colgroup>
        <col class="column-name">
        <col class="column-evk">
        <col class="column-user">
        <col class="column-date">
        <col class="column-status">
        <col class="column-actions">
      </colgroup>`;

    // --- HANDLERS ---
    const handleSort = ($el) => {
      const id = $el.data('sort');
      state.orderBy.reversed = (state.orderBy.id === id) ? !state.orderBy.reversed : false;
      state.orderBy.id = id;
      render();
    };

    const handleFilterToggle = () => {
      state.filterBox.visible = !state.filterBox.visible;
      if (state.filterBox.visible && !state.filterBox.input) {
        state.filterBox.input = applicationModel.getSessionUsername();
      }
      render();
    };

    const handleProjectOpen = ($el) => {
      const id = parseInt($el.data('id'));
      const status = parseInt($el.data('status'));
      const proceed = () => {
        clearInterval(pollProjects);
        if (status === projectStatus.ErrorInViite.value) {
          projectCollection.reOpenProjectById(id);
          eventbus.once("roadAddressProject:reOpenedProject", () => openProjectSteps(id));
        } else {
          openProjectSteps(id);
        }
      };
      if (status === projectStatus.InUpdateQueue.value || status === projectStatus.UpdatingToRoadNetwork.value) {
        new ConfirmPopup("Projektia päivitetään tieverkolle. Haluatko silti avata sen?", { successCallback: proceed });
      } else {
        proceed();
      }
    };

    const handleSync = () => {
      state.loading = true;
      render();
      fetchProjects();
    };

    const handleCreateNew = () => {
      clearInterval(pollProjects);
      modalContainer.close();
      applicationModel.setOpenProject(true);
      projectCollection.clearRoadAddressProjects();
      const newProj = { id: 0, name: '', startDate: '', additionalInfo: '', createdBy: '' };
      const projectMenu = resolveProjectMenu();
      if (projectMenu && _.isFunction(projectMenu.showProjectDetails)) {
        projectMenu.showProjectDetails(newProj, true, projectCollection, newProj);
      }
      if (applicationModel.isReadOnly()) $('.edit-mode-btn:visible').click();
    };

    const fetchProjects = () => {
      projectCollection.getProjects(state.onlyActive);
    };

    const openProjectSteps = (projectId) => {
      applicationModel.addSpinner();
      projectCollection.getProjectsWithLinksById(projectId).then(result => {
        hide();
        eventbus.trigger('roadAddress:openProject', result);
        if (applicationModel.isReadOnly()) $('.edit-mode-btn:visible').click();
      });
    };

    // --- TEMPLATES / HTML ---
    const template = () => {
      const filtered = state.projects.filter(p => 
        !state.filterBox.input || p.createdBy.toLowerCase().includes(state.filterBox.input.toLowerCase())
      );
      const sorted = filtered.sort((a, b) => {
        const res = headers[state.orderBy.id].sortFunc(a, b);
        return state.orderBy.reversed ? -res : res;
      });

      return `
        <div class="project-table-wrapper">
          <table class="project-table project-table-header">
            ${columnGroupTemplate()}
            <thead class="project-list-header">
              <tr>
                ${Object.keys(headers).map(id => {
                  const h = headers[id];
                  const icon = getSortIcon(id);
                  const columnClass = columnClassById[id];
                  const filterClass = state.filterBox.visible ? 'user-filter-input visible' : 'user-filter-input';
                  return `<th class="${columnClass}"><label>${h.toStr}<i data-sort="${id}" class="btn-icon sort fas ${icon}"></i>
                    ${id === "sortUser" ? `<i id="filterUser" class="btn-icon fas fa-filter"></i>` : ''}</label>
                    ${id === "sortUser" ? `<span class="${filterClass}" id="userFilterSpan"><input type="text" id="userNameBox" placeholder="Käyttäjätunnus" value="${state.filterBox.input}"></span>` : ''}
                  </th>`;
                }).join('')}
                <th class="column-actions"><div class="actions"><button class="new btn-primary">Uusi tieosoiteprojekti</button></div></th>
              </tr>
            </thead>
          </table>
          <div class="project-table-scroll">
            <table class="project-table project-table-body">
              ${columnGroupTemplate()}
              <tbody>
                ${sorted.map(proj => `
                  <tr class="project-list-row" data-id="${proj.id}">
                    <td class="project-name-cell column-name">${staticField(proj.name)}</td>
                    <td class="evk-cell column-evk">${staticField(proj.evks)}</td>
                    <td class="user-cell column-user">${staticField(proj.createdBy)}</td>
                    <td class="date-cell column-date">${staticField(dateutil.dateObjectToFinnishString(toComparableDate(proj.createdDate)))}</td>
                    <td class="status-cell column-status" title="${proj.statusInfo || 'Ei lisätietoja'}">${staticField(proj.statusDescription)}</td>
                    <td class="actions-cell column-actions"><button class="project-open ${proj.statusCode === projectStatus.ErrorInViite.value ? 'btn-new-error' : 'btn-primary'}" data-id="${proj.id}" data-status="${proj.statusCode}">
                      ${proj.statusCode === projectStatus.ErrorInViite.value ? 'Avaa uudelleen' : 'Avaa'}</button></td>
                  </tr>`).join('')}
              </tbody>
            </table>
          </div>
          <div class="content-footer">
            <label><input type="checkbox" id="OldAcceptedProjectsVisibleCheckbox" ${!state.onlyActive ? 'checked' : ''}><span>Näytä kaikki tieverkolle päivitetyt projektit</span></label>
            <i id="sync" class="fas refresh-button fa-sync-alt ${state.loading ? 'btn-spin' : ''}" title="Päivitä lista"></i>
          </div>
        </div>`;
    };

    const render = () => {
      $container.html(template());
      if (state.filterBox.visible) {
        const input = $container.find('#userNameBox')[0];
        if (input) { input.focus(); input.setSelectionRange(state.filterBox.input.length, state.filterBox.input.length); }
      }
    };

    function bindEvents() {
      eventutil.bindClick($container, '.sort', handleSort);
      eventutil.bindClick($container, '#filterUser', handleFilterToggle);
      eventutil.bindClick($container, '.project-open', handleProjectOpen);
      eventutil.bindClick($container, '#sync', handleSync);
      eventutil.bindClick($container, '.new', handleCreateNew);

      $container.on('input', '#userNameBox', (e) => { state.filterBox.input = e.target.value; render(); });
      $container.on('change', '#OldAcceptedProjectsVisibleCheckbox', (e) => { state.onlyActive = !e.target.checked; fetchProjects(); });

      eventbus.off('roadAddressProjects:fetched').on('roadAddressProjects:fetched', (projects) => {
        state.projects = projects.filter(p => p.statusCode !== projectStatus.Deleted.value);
        state.loading = false;
        render();
      });
    }

    function show() {
      state.projects = [];
      state.filterBox.input = '';
      state.filterBox.visible = false;
      state.loading = true;
      state.onlyActive = true;
      $container = $('<div id="project-list-root"></div>');
      bindEvents();
      render();
      modalContainer = applicationApi.getModalContainer({ onClose: hide });
      modalContainer.open({ title: 'Tieosoiteprojektit', content: $container });
      fetchProjects();
      pollProjects = setInterval(() => projectCollection.getProjectStates(state.projects.map(p => p.id)), 30000);
    }

    function hide() {
      if (pollProjects) clearInterval(pollProjects);
      pollProjects = null;
      state.loading = false;
      $(document).off('.projectList');
      if (modalContainer) modalContainer.close();
      if ($container) { $container.remove(); $container = null; }
    }

    function cleanup() {
      hide();
      eventbus.off('roadAddressProjects:fetched roadAddressProjectStates:fetched');
    }

    return { show, hide, cleanup, getElement: () => $container };
}