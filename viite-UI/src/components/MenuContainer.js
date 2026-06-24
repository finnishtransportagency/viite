/* This is used as a shared container for the right menu panel for both node and project menus and it provides header, body and footer sections.

Usage:
import { MenuContainer } from '@/components/MenuContainer.js';

const menu = MenuContainer();
menu.setHeader('<h3>Valikko</h3>');
menu.setBody(`<div>${contentHtml}</div>`);
menu.setFooter('<button class="btn-primary">Tallenna</button>');
*/

export function MenuContainer() {
	let defaultCloseCallback = () => undefined;
	let closeCallback = defaultCloseCallback;

	// 1. Create elements directly
	const root = document.createElement('div');
	root.className = 'menu-container-layout';

	const header = document.createElement('header');
	header.className = 'menu-header';
	header.style.display = 'none';

	const headerContent = document.createElement('div');
	headerContent.className = 'menu-header-content';

	const closeButton = document.createElement('button');
	closeButton.className = 'menu-close-btn';
	closeButton.type = 'button';
	closeButton.title = 'Sulje';
	closeButton.innerHTML = '<i class="fas fa-window-close"></i>';

	const body = document.createElement('main');
	body.className = 'menu-body';

	const footer = document.createElement('footer');
	footer.className = 'menu-footer';
	footer.style.display = 'none';

	// 2. Assemble structure
	header.append(headerContent, closeButton);
	root.append(header, body, footer);

	// 3. Third approach: Event listener assignment 
	// (Directly reference the created variable, no selector needed)
	closeButton.addEventListener('click', (event) => {
		event.preventDefault();
		closeCallback();
	});

	const setHeader = (html, onCloseOverride) => {
		closeCallback = _.isFunction(onCloseOverride) ? onCloseOverride : defaultCloseCallback;
		headerContent.innerHTML = html || '';
		header.style.display = html ? '' : 'none';
	};

	const setDefaultClose = (callback) => {
		defaultCloseCallback = _.isFunction(callback) ? callback : () => undefined;
		closeCallback = defaultCloseCallback;
	};

	const setBody = (html) => {
		body.innerHTML = html || '';
	};

	const setFooter = (html) => {
		footer.innerHTML = html || '';
		footer.style.display = html ? '' : 'none';
	};

	const getBody = () => body;

	return { root, setHeader, setBody, setFooter, setDefaultClose, getBody };
}