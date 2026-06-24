import { ConfirmPopup } from '@components/modals/ConfirmPopup.js';
import { Spinner } from '@components/spinner/Spinner.js';
import { zoomlevels } from '@utils/ZoomLevels.js';
import { searchLocation } from './LocationSearch.js';
import { moveMapToCoordinates } from '@view/map/MapView.js';
import { addNodesToMap, fetchNodesAndJunctionsFromCurrentMap } from '@view/map/layers/NodeLayer.js';
import { openSelectedNodesAndJunctionTemplates } from './SelectedNodesAndJunctions.js';

/**
 * NodeCollection - Manages road nodes and junctions data
 * 
 * Handles node-related operations including:
 * - Node data management and retrieval
 * - Template management for nodes and junctions
 * - Road attribute-based node searching
 * - Backend integration for node operations
 * - Node point and junction template handling
 */
export function NodeCollection(backend) {
	let nodes = [];
	let nodesWithAttributes = [];
	let mapTemplates = [];
	let userNodePointTemplates = [];
	let userJunctionTemplates = [];
	const saving = 'node-saving';
	let map;

	function setMap(m) {
		map = m;
	}

	function setMapTemplates(templates) {
		mapTemplates = templates;
	}

	function setUserTemplates(nodePointTemplates, junctionTemplates) {
		userNodePointTemplates = nodePointTemplates;
		userJunctionTemplates = junctionTemplates;
	}

	function setNodes(list) {
		nodes = list;
	}

	function getNodeByNodeNumber(nodeNumber) {
		return _.find(nodes, function (node) {
			return node.nodeNumber === nodeNumber;
		});
	}

	function getNodesWithAttributes() {
		return nodesWithAttributes;
	}

	function setNodesWithAttributes(list) {
		nodesWithAttributes = list;
	}

	function applyFetchedNodesAndJunctions(fetchResult, zoom) {
		if (!fetchResult) return;

		const resultNodes = fetchResult.nodes;
		const templates = {
			nodePoints: fetchResult.nodePointTemplates,
			junctions: fetchResult.junctionTemplates
		};

		setNodes(resultNodes);
		setMapTemplates(templates);

		addNodesToMap(resultNodes, templates, zoom);
	}

	async function fetchAndApplyNodesAndJunctions(zoom) {
		const targetZoom = _.isNumber(zoom)
			? zoom
			: zoomlevels.getViewZoom(map) + 1;

		const fetchResult = await fetchNodesAndJunctionsFromCurrentMap(targetZoom);
		applyFetchedNodesAndJunctions(fetchResult, targetZoom);
		return fetchResult;
	}

	// Fits map view to include all nodes returned by the latest node search.
	function fitMapToSearchResults() {
		if (_.isEmpty(nodesWithAttributes)) return;
		const coords = [];
		_.each(nodesWithAttributes, function (node) {
			coords.push([node.coordinates.x, node.coordinates.y]);
		});
		map.getView().fit(new ol.geom.Polygon([coords]), map.getSize());
	}

	function getNodesByRoadAttributes(roadAttributes) {
		return new Promise((resolve, reject) => {
			backend.getNodesByRoadAttributes(roadAttributes, function (result) {
				if (result.success) {
					const searchResult = result.nodes;
					setNodesWithAttributes(searchResult);
					resolve(searchResult);
				} else {
					Spinner.hide();
					new ConfirmPopup(result.errorMessage, { type: "alert" });
					reject(new Error(result.errorMessage));
				}
			});
		});
	}

	function getNodePointTemplatesByCoordinates(coordinates) {
		return _.filter(mapTemplates.nodePoints, function (nodePointTemplate) {
			return _.isEqual(nodePointTemplate.coordinates, coordinates);
		});
	}

	function getJunctionTemplateByCoordinates(coordinates) {
		return _.filter(mapTemplates.junctions, function (junctionTemplate) {
			return _.find(junctionTemplate.junctionPoints, function (junctionPoint) {
				return _.isEqual(junctionPoint.coordinates, coordinates);
			});
		});
	}

	/**
     * Moves to node/junction template location and handles node data loading.
     *
     * Process:
     * 1. Searches location based on road address
     * 2. Moves map to found coordinates
     * 3. Fetches and processes node data for the location
     * 4. Opens template form with filtered data
     * 5. Updates map with new node/junction template information
     */
	const moveToLocation = async function (template) {
		if (!template) return;

		Spinner.show('moveToLocation');

		try {
			// Search for location based on road address information
			const searchResults = await searchLocation(
				`${template.roadNumber} ${template.roadPartNumber} ${template.addrM} ${template.track}`
			);

			if (searchResults.length === 0) return;

			const result = searchResults[0];

			// Move map to found location with appropriate zoom level

			moveMapToCoordinates(map, {
				lon: result.lon,
				lat: result.lat,
				zoom: zoomlevels.minZoomForJunctions
			});


			// Fetch node data for the selected location
			const fetchedNodesAndJunctions = await fetchAndApplyNodesAndJunctions(zoomlevels.minZoomForJunctions);

			if (fetchedNodesAndJunctions && (fetchedNodesAndJunctions.junctionTemplates || fetchedNodesAndJunctions.nodePointTemplates)) {
				const referencePoint = {
					// Calculate reference point for template filtering
					x: parseFloat(result.lon.toFixed(3)),
					y: parseFloat(result.lat.toFixed(3))
				};

				const templates = {
					nodePoints: fetchedNodesAndJunctions.nodePointTemplates,
					junctions: fetchedNodesAndJunctions.junctionTemplates
				};

				// Open template form with filtered data matching reference point
				const coordinateToleranceMeters = 0.01; // 1 centimeter tolerance to avoid bug VIITE-3697

				const isSameLocation = function(coords1, coords2) {
					if (!coords1 || !coords2) return false;
					return Math.abs(coords1.x - coords2.x) < coordinateToleranceMeters && 
                    Math.abs(coords1.y - coords2.y) < coordinateToleranceMeters;
				};

				openSelectedNodesAndJunctionTemplates({
					nodePoints: _.filter(templates.nodePoints, function (nodePoint) {
						return isSameLocation(nodePoint.coordinates, referencePoint);
					}),
					junctions: _.filter(templates.junctions, function (junction) {
						return _.some(junction.junctionPoints, function (junctionPoint) {
							return isSameLocation(junctionPoint.coordinates, referencePoint);
						});
					})
				});
			}
		} catch (error) {
			console.error('Error in moveToLocation:', error);
		} finally {
			// Ensure spinner is always removed
			Spinner.hide('moveToLocation');
		}
	};

	function saveNodeToBackend(node, onSuccess, onFail) {
		const fail = function (message) {
			onFail(message.errorMessage || 'Solmun tallennus epäonnistui.', saving);
		};

		if (!_.isUndefined(node)) {
			Spinner.show(saving);
			if (node.id) {
				backend.updateNodeInfo(node, function (result) {
					if (result.success) {
						Spinner.hide(saving);
						onSuccess();
					} else {
						fail(result);
					}
				}, fail);
			} else {
				backend.createNodeInfo(node, function (result) {
					if (result.success) {
						Spinner.hide(saving);
						onSuccess();
					} else {
						fail(result);
					}
				}, fail);
			}
		}
	}

	// Opens a node point template by id and moves map to the template location.
	function openNodePointTemplate(payload) {
		const id = _.isObject(payload) ? payload.id : payload;
		const nodePointTemplate = _.find(userNodePointTemplates, function (template) {
			return template.id === parseInt(id, 10);
		});
		if (_.isUndefined(nodePointTemplate)) {
			backend.getNodePointTemplateById(id, function (nodePointTemplateFetched) {
				moveToLocation(nodePointTemplateFetched);
			});
		} else {
			moveToLocation(nodePointTemplate);
		}
	}

	// Opens a junction template by id and optional row coordinates, then moves map to it.
	function openJunctionTemplate(payload) {
		const id = _.isObject(payload) ? payload.id : payload;
		const coordinates = _.isObject(payload) ? payload.coordinates : null;
		const rowData = _.isObject(payload) ? payload.rowData : null;

		const junctionTemplate = _.find(userJunctionTemplates, function (template) {
			if (template.id !== parseInt(id, 10)) {
				return false;
			}

			if (!coordinates) {
				return true;
			}

			return _.some(template.junctionPoints || [], function (junctionPoint) {
				return _.isEqual(junctionPoint.coordinates, coordinates);
			});
		});

		const fallbackJunctionTemplate = junctionTemplate || _.find(userJunctionTemplates, function (template) {
			return template.id === parseInt(id, 10);
		});

		const templateForLocation = function (template) {
			if (!rowData) {
				return template;
			}

			return _.assign({}, template, {
				roadNumber: Number(rowData.roadNumber),
				track: Number(rowData.track),
				roadPartNumber: Number(rowData.roadPartNumber),
				addrM: Number(rowData.addrM)
			});
		};

		if (_.isUndefined(fallbackJunctionTemplate)) {
			backend.getJunctionTemplateById(id, function (junctionTemplateFetched) {
				moveToLocation(templateForLocation(junctionTemplateFetched));
			});
		} else {
			moveToLocation(templateForLocation(fallbackJunctionTemplate));
		}
	}

	return {
		setMapTemplates: setMapTemplates,
		setUserTemplates: setUserTemplates,
		setNodes: setNodes,
		getNodeByNodeNumber: getNodeByNodeNumber,
		getNodesWithAttributes: getNodesWithAttributes,
		setNodesWithAttributes: setNodesWithAttributes,
		applyFetchedNodesAndJunctions: applyFetchedNodesAndJunctions,
		fetchAndApplyNodesAndJunctions: fetchAndApplyNodesAndJunctions,
		fitMapToSearchResults: fitMapToSearchResults,
		getNodesByRoadAttributes: getNodesByRoadAttributes,
		getNodePointTemplatesByCoordinates: getNodePointTemplatesByCoordinates,
		getJunctionTemplateByCoordinates: getJunctionTemplateByCoordinates,
		setMap: setMap,
		saveNodeToBackend: saveNodeToBackend,
		moveToLocation: moveToLocation,
		openNodePointTemplate: openNodePointTemplate,
		openJunctionTemplate: openJunctionTemplate
	};
}
