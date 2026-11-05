const { FlatCompat } = require("@eslint/eslintrc");
const js = require("@eslint/js");

// FlatCompat is used for backwards compatibility with older ESLint configuration formats, allowing us to extend from .eslintrc.cjs
const compat = new FlatCompat({
  baseDirectory: __dirname,
  recommendedConfig: js.configs.recommended,
  allConfig: js.configs.all
});

module.exports = [
  {
    ignores: ["ol-custom.js"]
  },
  ...compat.config(require("./.eslintrc.cjs"))
];
