# Frontend Code Review — viite-UI/src

Status legend: `⬜ pending` | `✅ passed` | `⚠️ smells`

---

## model/

| File | Status | Notes |
|------|--------|-------|
| `model/ApplicationModel.js` | ⬜ pending | |
| `model/LocationSearch.js` | ⬜ pending | |
| `model/NodeCollection.js` | ⬜ pending | |
| `model/ProjectChangeInfoModel.js` | ⬜ pending | |
| `model/ProjectCollection.js` | ⬜ pending | |
| `model/RoadCollection.js` | ⬜ pending | |
| `model/RoadNameCollection.js` | ⬜ pending | |
| `model/SelectedLinkProperty.js` | ⬜ pending | |
| `model/SelectedNodesAndJunctions.js` | ⬜ pending | |
| `model/SelectedProjectLink.js` | ⬜ pending | |
| `model/TileMapCollection.js` | ⬜ pending | |

---

## utils/

| File | Status | Notes |
|------|--------|-------|
| `utils/BackendUtils.js` | ✅ passed | Request helper now uses native Promise + safer cancellation; redundant async pass-through removed |
| `utils/DateUtils.js` | ✅ passed | Moment-based strict parsing/formatting and non-mutating date add helper |
| `utils/EnumerationUtils.js` | ✅ passed | Lookup now returns safe fallback for unknown administrative class |
| `utils/EnvironmentUtils.js` | ✅ passed | URL API-based parsing and single environment lookup per call |
| `utils/eventbus.js` | ✅ passed | Added eventbus factory and native Promise-based once helper |
| `utils/GeometryUtils.js` | ✅ passed | Zero-length vector guarded; midpoint shape normalized; distance logic deduplicated |
| `utils/LocationInputParser.js` | ✅ passed | Coordinate parser now supports signed/decimal input and unified road pattern handling |
| `utils/StyleRule.js` | ✅ passed | Throws Error objects, adds reset lifecycle method, and fixes named-rule assignment |
| `utils/UserManagementApi.js` | ✅ passed | Unified function checks and normalized error extraction across methods |
| `utils/ViiteConstants.js` | ✅ passed | Small, focused constants file |
| `utils/ViiteEnumerations.js` | ✅ passed | Enumerations are deep-frozen and lifecycle naming aliases clarified |
| `utils/ZoomLevels.js` | ✅ passed | Named zoom defaults introduced and exported config made immutable |

---

## view/MainMenu

| File | Status | Notes |
|------|--------|-------|
| `view/MainMenu.js` | ⬜ pending | |

---

## view/admin-panel/

| File | Status | Notes |
|------|--------|-------|
| `view/admin-panel/AdminPanel.js` | ⬜ pending | |
| `view/admin-panel/DynamicLinkNetworkContent.js` | ⬜ pending | |
| `view/admin-panel/user-management/AddUserForm.js` | ⬜ pending | |
| `view/admin-panel/user-management/Dropdowns.js` | ⬜ pending | |
| `view/admin-panel/user-management/FormValidation.js` | ⬜ pending | |
| `view/admin-panel/user-management/Main.js` | ⬜ pending | |
| `view/admin-panel/user-management/UpdateUsersForm.js` | ⬜ pending | |
| `view/admin-panel/user-management/View.js` | ⬜ pending | |



## view/link-info/

| File | Status | Notes |
|------|--------|-------|
| `view/link-info/LinkInfo.js` | ⬜ pending | |

---

## view/map/

| File | Status | Notes |
|------|--------|-------|
| `view/map/MapOverlay.js` | ⬜ pending | |
| `view/map/MapView.js` | ⬜ pending | |
| `view/map/ProjectLinkStyler.js` | ⬜ pending | |
| `view/map/RoadLinkStyler.js` | ⬜ pending | |
| `view/map/layers/Layer.js` | ⬜ pending | |
| `view/map/layers/LinkPropertyLayer.js` | ⬜ pending | |
| `view/map/layers/NodeLayer.js` | ⬜ pending | |
| `view/map/layers/ProjectLinkLayer.js` | ⬜ pending | |
| `view/map/layers/RoadLayer.js` | ⬜ pending | |
| `view/map/markers/CalibrationPointMarker.js` | ⬜ pending | |
| `view/map/markers/JunctionMarker.js` | ⬜ pending | |
| `view/map/markers/JunctionTemplateMarker.js` | ⬜ pending | |
| `view/map/markers/LinkPropertyMarker.js` | ⬜ pending | |
| `view/map/markers/NodeMarker.js` | ⬜ pending | |
| `view/map/markers/NodePointTemplateMarker.js` | ⬜ pending | |
| `view/map/markers/ProjectLinkMarker.js` | ⬜ pending | |
| `view/map/markers/RoadMarker.js` | ⬜ pending | |
| `view/map/markers/ScaleBar.js` | ⬜ pending | |
| `view/map/markers/ZoomBox.js` | ⬜ pending | |

---

## view/node-menu/

| File | Status | Notes |
|------|--------|-------|
| `view/node-menu/DataTable.js` | ⬜ pending | |
| `view/node-menu/NodeDataMenu.js` | ⬜ pending | |
| `view/node-menu/NodeEditor.js` | ⬜ pending | |
| `view/node-menu/NodeMenu.js` | ⬜ pending | |
| `view/node-menu/NodeSearchMenu.js` | ⬜ pending | |

---

## view/project-menu/

| File | Status | Notes |
|------|--------|-------|
| `view/project-menu/ProjectChangeTable.js` | ⚠️ smells | See details below |
| `view/project-menu/ProjectMenu.js` | ⚠️ smells | See details below |
| `view/project-menu/project-action-menu/ProjectActionMenu.js` | ⚠️ smells | See details below |
| `view/project-menu/project-details/ProjectDetailsForm.js` | ⚠️ smells | See details below |
| `view/project-menu/project-details/ValidationUtils.js` | ⚠️ smells | See details below |
| `view/project-menu/project-link-editor/DevTool.js` | ✅ passed | |
| `view/project-menu/project-link-editor/ProjectLinkEditor.js` | ⚠️ smells | See details below |
| `view/project-menu/project-link-editor/ProjectLinkEditorHTML.js` | ⚠️ smells | See details below |
| `view/project-menu/project-link-editor/ProjectLinkEditorLogic.js` | ✅ passed | |
| `view/project-menu/project-list/ProjectList.js` | ⚠️ smells | See details below |

---

## view/road-address-inspection/

| File | Status | Notes |
|------|--------|-------|
| `view/road-address-inspection/RoadAddressBrowserForm.js` | ⬜ pending | |
| `view/road-address-inspection/RoadAddressBrowserWindow.js` | ⬜ pending | |
| `view/road-address-inspection/RoadAddressChangesBrowserWindow.js` | ⬜ pending | |


---





---

## Review Details


