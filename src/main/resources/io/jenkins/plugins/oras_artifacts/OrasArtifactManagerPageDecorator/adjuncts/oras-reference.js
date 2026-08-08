(function (d) {
    'use strict';

    var ARTIFACT_MARKER = '/artifact/';
    var VIEW_LINK_SUFFIX = '/*view*/';
    var ENDPOINT_SEGMENT = 'oras-artifact-manager/reference';

    function getViewLink(row) {
        return row.querySelector('a[href$="' + VIEW_LINK_SUFFIX + '"]');
    }

    function getArtifactListRows(scope) {
        var root = scope || d;
        var rows = [];
        root.querySelectorAll('table.fileList tr').forEach(function (row) {
            if (getViewLink(row)) {
                rows.push(row);
            }
        });
        return rows;
    }

    function getArtifactFileLink(row) {
        var links = row.querySelectorAll('a[href]:not([href$="' + VIEW_LINK_SUFFIX + '"])');
        for (var i = 0; i < links.length; i++) {
            var link = links[i];
            var href = link.getAttribute('href');
            if (!href || href.indexOf('/*zip*/') !== -1) continue;
            var label = (link.textContent || '').trim();
            if (label === '(all files in zip)') continue;
            return link;
        }
        return null;
    }

    function endpointFor(href) {
        var idx = href.indexOf(ARTIFACT_MARKER);
        if (idx === -1) return null;
        var runUrl = href.substring(0, idx + 1);
        var relativePath = decodeURIComponent(href.substring(idx + ARTIFACT_MARKER.length));
        return runUrl + ENDPOINT_SEGMENT + '?path=' + encodeURIComponent(relativePath);
    }

    var ICON_SOURCE_CLASS = 'oras-artifact-manager-icon-source';
    var ICON_CLASS = 'oras-artifact-manager-icon';

    function buildIcon(reference) {
        var template = d.getElementById('oras-artifact-manager-icon-template');
        if (!template) return null;
        var source = template.querySelector('.' + ICON_SOURCE_CLASS);
        if (!source) return null;
        var icon = source.cloneNode(true);
        icon.classList.remove(ICON_SOURCE_CLASS);
        icon.classList.add(ICON_CLASS);
        icon.style.setProperty('color', 'var(--text-color, currentColor)', 'important');
        icon.setAttribute('tooltip', reference.reference);
        icon.addEventListener('click', function (e) {
            e.preventDefault();
            e.stopPropagation();
            if (navigator.clipboard) {
                navigator.clipboard.writeText(reference.reference);
            }
        });
        return icon;
    }

    function decorateRow(row) {
        if (row.dataset.orasRef) return;
        var fileLink = getArtifactFileLink(row);
        var viewLink = getViewLink(row);
        if (!fileLink || !viewLink) return;
        var endpoint = endpointFor(fileLink.getAttribute('href'));
        if (!endpoint) return;
        row.dataset.orasRef = '1';
        fetch(endpoint, {headers: {Accept: 'application/json'}})
            .then(function (response) {
                return response.ok ? response.json() : null;
            })
            .then(function (reference) {
                if (!reference) return;
                var icon = buildIcon(reference);
                if (!icon) return;
                viewLink.insertAdjacentElement('afterend', icon);
                if (window.Behaviour && typeof window.Behaviour.applySubtree === 'function') {
                    window.Behaviour.applySubtree(icon, true);
                }
            })
            .catch(function () {
                // Nothing archived at this path through ORAS, or no permission: stay silent.
            });
    }

    function scan(scope) {
        getArtifactListRows(scope).forEach(decorateRow);
    }

    function init() {
        scan(d);
        window.setTimeout(function () {
            scan(d);
        }, 500);
        if (window.MutationObserver) {
            var observer = new MutationObserver(function (mutations) {
                mutations.forEach(function (mutation) {
                    mutation.addedNodes.forEach(function (node) {
                        if (node.nodeType === Node.ELEMENT_NODE) {
                            scan(node);
                        }
                    });
                });
            });
            observer.observe(d.body, {childList: true, subtree: true});
        }
    }

    if (d.readyState === 'loading') {
        d.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})(document);
