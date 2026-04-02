/**
 * Builds composable OpenLayers rules from chained attribute filters.
 * Wraps logical and comparison filter creation behind a small fluent API.
 */
const contextFilter = function (attributeName, attributeValue, context) {
    return new OpenLayers.Filter.Function({
      evaluate: function () {
        return context[attributeName] === attributeValue;
      }
    });
  };
  const isInContextFilter = function (attributeName, attributeValues, context) {
    const filters = _.map(attributeValues, function (value) {
      return contextFilter(attributeName, value, context);
    });
    return new OpenLayers.Filter.Logical({
      type: OpenLayers.Filter.Logical.OR,
      filters: filters
    });
  };
  const featureAttributeFilter = function (attributeName, attributeValue) {
    return new OpenLayers.Filter.Comparison({
      type: OpenLayers.Filter.Comparison.EQUAL_TO,
      property: attributeName,
      value: attributeValue
    });
  };
  const isInFeatureAttributeFilter = function (attributeName, attributeValues) {
    const filters = _.map(attributeValues, function (value) {
      return featureAttributeFilter(attributeName, value);
    });
    return new OpenLayers.Filter.Logical({
      type: OpenLayers.Filter.Logical.OR,
      filters: filters
    });
  };
  const createUseFunction = function (state) {
    return function (style) {
      return new OpenLayers.Rule({
        filter: new OpenLayers.Filter.Logical({
          type: OpenLayers.Filter.Logical.AND,
          filters: state.filters
        }),
        symbolizer: style
      });
    };
  };
  const createWhereFunction = function (state) {
    return function (attributeName, context) {
      return {
        is: function (attributeValue) {
          const filter = context ? contextFilter(attributeName, attributeValue, context)
            : featureAttributeFilter(attributeName, attributeValue);
          return newIsObject({
            filters: state.filters.concat([filter])
          });
        },
        isIn: function (attributeValues) {
          const filter = context ? isInContextFilter(attributeName, attributeValues, context)
            : isInFeatureAttributeFilter(attributeName, attributeValues);
          return newIsObject({
            filters: state.filters.concat([filter])
          });
        }
      };
    };
  };
  const newIsObject = function (state) {
    return {
      and: createWhereFunction(state),
      use: createUseFunction(state)
    };
  };
export function OpenLayersRule() {
  const state = {
    filters: []
  };
  return {
    where: createWhereFunction(state)
  };
}

window.OpenLayersRule = OpenLayersRule;
