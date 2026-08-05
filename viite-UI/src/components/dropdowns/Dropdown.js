// Common dropdown component for rendering select elements
export function dropdown(config) {
	const {
		id = '',
		className = '',
		defaultValue = '',
		options = [],
		disabled = false,
		style = ''
	} = config;

	const disabledAttr = disabled ? 'disabled' : '';
	const styleAttr = style ? `style="${style}"` : '';
	const classes = ['dropdown', className].filter(Boolean).join(' ');
	const classAttr = classes ? `class="${classes}"` : '';

	// Generate options HTML
	const optionsHtml = options.map(option => {
		if (typeof option === 'string') {
			const isSelected = option === defaultValue ? 'selected' : '';
			return `<option value="${option}" ${isSelected}>${option}</option>`;
		} else if (typeof option === 'object' && option.value !== undefined) {
			// Coerce to string: defaultValue may be numeric (e.g. trackCode) while option.value is always a string.
			const isSelected = option.selected || String(option.value) === String(defaultValue) || option.text === defaultValue ? 'selected' : '';
			const isDisabled = option.disabled ? 'disabled hidden' : '';
			const idAttr = option.id ? `id="${option.id}"` : '';
			return `<option value="${option.value}" ${isSelected} ${isDisabled} ${idAttr}>${option.text || option.value}</option>`;
		}
		console.warn('dropdown: unknown option shape', option);
		return '';
	}).join('');

	return `
      <select 
        id="${id}" 
        ${classAttr}
        ${styleAttr}
        ${disabledAttr}
      >
        ${optionsHtml}
      </select>
    `.trim();
}
