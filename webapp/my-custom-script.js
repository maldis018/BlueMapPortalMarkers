/*
 * BlueMapPortalMarkers — BlueMap web-app script addon
 * ----------------------------------------------------
 * Makes the "Go to linked portal" deep-link inside a portal popup navigate on
 * a normal left-click.
 *
 * Two BlueMap behaviours get in the way of a plain <a href="#..."> link:
 *
 *  1. BlueMap's map controls cancel the anchor's default navigation. We
 *     intercept the click in the capture phase and drive the URL hash directly.
 *
 *  2. When a popup opens, BlueMap registers global "click-away" listeners that
 *     reference the marker's element. Following a deep-link switches maps, which
 *     disposes every marker (its element becomes undefined) but does NOT remove
 *     those listeners — so the next mousedown/keydown throws
 *     "this.element is undefined" repeatedly. Before navigating we fire a
 *     synthetic `window` mousedown so BlueMap tears that listener down while the
 *     marker still exists. (`window` mousedown only triggers the click-away
 *     cleanup; the map's drag controls listen on the canvas, so there's no
 *     side effect.)
 *
 * Install: copy to your BlueMap webroot (e.g. bluemap/web/) and register it in
 * plugins/BlueMap/webapp.conf under `scripts:`. See the project README.
 */
document.addEventListener('click', function (e) {
    const a = e.target.closest && e.target.closest('a[href^="#"]');
    if (a && a.closest('.bm-marker-poi-label, .bm-marker-popup')) {
        e.stopPropagation();
        window.dispatchEvent(new MouseEvent('mousedown'));
        window.location.hash = a.getAttribute('href').slice(1);
    }
}, true);
