// Displays road address projects in a table format, allowing sorting, filtering and opening projects.
// Polls for project state updates every 30 seconds.
import { checkbox } from '@components/checkbox/Checkbox.js';
import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { ModalContainer } from '@components/modals/ModalContainer.js';
import { Spinner } from '@components/spinner/Spinner.js';
import { ProjectActionMenu } from '@view/project-menu/project-action-menu/ProjectActionMenu.js';
import { ProjectMenu } from '@view/project-menu/ProjectMenu.js';
import { ViiteEnumerations } from '@utils/ViiteEnumerations.js';
import { dateutil } from '@utils/DateUtils.js';
import { eventbus } from '@utils/Eventbus.js';

import { getStartupParameters, getSessionUsername } from '@model/ApplicationModel.js';

export function ProjectList(options = {}) {
  const canUseDevTools = getStartupParameters()?.roles?.includes('dev') ?? false;
  
  let projectMenuInstance = null;

  const ensureProjectMenu = () => {
    if (projectMenuInstance) {
      return projectMenuInstance;
    }

    const actionMenu = new ProjectActionMenu({
      projectCollection: options.projectCollection,
      map: options.map,
      eventbus: options.eventbus || eventbus,
      canValidateProject: canUseDevTools,
      backend: options.backend,
      projectChangeInfoModel: options.projectChangeInfoModel,
      mainMenu: options.mainMenu
    });

    projectMenuInstance = new ProjectMenu('#menu-container', options.eventbus || eventbus, {
      projectMenu: actionMenu,
      projectCollection: options.projectCollection,
      projectLinkLayer: options.projectLinkLayer,
      selectedProjectLinkProperty: options.selectedProjectLinkProperty,
      mainMenu: options.mainMenu,
      canUseDevTools: canUseDevTools,
      map: options.map,
      backend: options.backend,
      projectChangeTable: actionMenu.getProjectChangeTable(),
      projectChangeInfoModel: options.projectChangeInfoModel,
      menu: options.menu
    });

    return projectMenuInstance;
  };
    const projectStatus = ViiteEnumerations.ProjectStatus;

    const state = {
      projects: [],
      orderBy: { id: "sortStatus", reversed: false },
      filterBox: { input: "", visible: false },
      loading: false,
      loadingStartedAt: 0,
      loadingTimeoutId: null,
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
        state.filterBox.input = getSessionUsername();
      }
      render();
    };

    const handleProjectOpen = ($el) => {
      const id = parseInt($el.data('id'), 10);
      const status = parseInt($el.data('status'), 10);
      const proceed = () => {
        clearInterval(pollProjects);
        ensureProjectMenu();
        if (status === projectStatus.ErrorInViite.value) {
          options.projectCollection.reOpenProjectById(id);
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
      if (state.loading) {
        return;
      }
      state.loading = true;
      state.loadingStartedAt = Date.now();
      if (state.loadingTimeoutId) {
        clearTimeout(state.loadingTimeoutId);
        state.loadingTimeoutId = null;
      }
      render();
      fetchProjects();
    };

    const handleCreateNew = () => {
      clearInterval(pollProjects);
      modalContainer.close();
      options.projectCollection.clearRoadAddressProjects();
      const newProj = { id: 0, name: '', startDate: '', additionalInfo: '', createdBy: '' };
      const projectMenu = ensureProjectMenu();
      if (projectMenu && _.isFunction(projectMenu.showProjectDetails)) {
        projectMenu.showProjectDetails(newProj, true, options.projectCollection, newProj);
      }
      $('.edit-mode-btn:visible').click();
    };

    const fetchProjects = () => {
      options.projectCollection.getProjects(state.onlyActive);
    };

    const openProjectSteps = (projectId) => {
      Spinner.show();
      options.projectCollection.getProjectsWithLinksById(projectId).then(result => {
        hide();
        eventbus.trigger('roadAddress:openProject', result);
        $('.edit-mode-btn:visible').click();
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
            ${checkbox({
              id: 'OldAcceptedProjectsVisibleCheckbox',
              label: 'Näytä kaikki tieverkolle päivitetyt projektit',
              checked: !state.onlyActive
            })}
            <i id="sync" class="fas refresh-button fa-sync-alt ${state.loading ? 'refresh-spin' : ''}" title="Päivitä lista"></i>
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

    const stopLoading = () => {
      const minimumSpinnerMs = 350;
      const elapsedMs = Date.now() - state.loadingStartedAt;
      const remainingMs = Math.max(0, minimumSpinnerMs - elapsedMs);

      if (state.loadingTimeoutId) {
        clearTimeout(state.loadingTimeoutId);
        state.loadingTimeoutId = null;
      }

      if (remainingMs === 0) {
        state.loading = false;
        render();
        return;
      }

      state.loadingTimeoutId = setTimeout(() => {
        state.loading = false;
        state.loadingTimeoutId = null;
        render();
      }, remainingMs);
    };

    function bindEvents() {
      $($container).off('click', '.sort').on('click', '.sort', function(e) { handleSort($(this), e); });
      $($container).off('click', '#filterUser').on('click', '#filterUser', function(e) { handleFilterToggle($(this), e); });
      $($container).off('click', '.project-open').on('click', '.project-open', function(e) { handleProjectOpen($(this), e); });
      $($container).off('click', '#sync').on('click', '#sync', function(e) { handleSync($(this), e); });
      $($container).off('click', '.new').on('click', '.new', function(e) { handleCreateNew($(this), e); });

      $container.on('input', '#userNameBox', (e) => { state.filterBox.input = e.target.value; render(); });
      $container.on('change', '#OldAcceptedProjectsVisibleCheckbox', (e) => { state.onlyActive = !e.target.checked; fetchProjects(); });

      eventbus.off('roadAddressProjects:fetched').on('roadAddressProjects:fetched', (projects) => {
        state.projects = projects.filter(p => p.statusCode !== projectStatus.Deleted.value);
        stopLoading();
      });
    }

    function show() {
      state.projects = [];
      state.filterBox.input = '';
      state.filterBox.visible = false;
      state.loading = false;
      state.loadingStartedAt = 0;
      state.onlyActive = true;
      ensureProjectMenu();
      $container = $('<div id="project-list-root"></div>');
      bindEvents();
      render();
      modalContainer = new ModalContainer({ onClose: hide });
      modalContainer.open({ title: 'Tieosoiteprojektit', content: $container });
      fetchProjects();
      pollProjects = setInterval(() => options.projectCollection.getProjectStates(state.projects.map(p => p.id)), 30000);
    }

    function hide() {
      if (pollProjects) clearInterval(pollProjects);
      pollProjects = null;
      if (state.loadingTimeoutId) {
        clearTimeout(state.loadingTimeoutId);
        state.loadingTimeoutId = null;
      }
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