// Copies third-party browser libraries into the plugin's webapp so Jenkins serves them
// from /plugin/jenkinsocr/js/vendor/. Run by Maven through `npm run mvnbuild`.
import { copyFileSync, mkdirSync } from "node:fs";

const target = "src/main/webapp/js/vendor";
mkdirSync(target, { recursive: true });

// The CSP build avoids eval, so it also works when Jenkins enforces a Content-Security-Policy.
copyFileSync("node_modules/heic-to/dist/csp/heic-to.js", `${target}/heic-to.js`);
copyFileSync("node_modules/heic-to/LICENSE", `${target}/heic-to.LICENSE`);
