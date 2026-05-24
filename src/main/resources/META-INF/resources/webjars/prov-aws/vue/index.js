import { VBtn as e, VChip as t, VIcon as n, useI18nStore as r } from "@ligoj/host";
import { h as i } from "vue";
//#region src/i18n/en.js
var a = {
	"service:prov:aws:console": "AWS Console",
	"service:prov:aws:access-key-id": "Access Key Id",
	"service:prov:aws:secret-access-key": "Secret Access Key",
	"service:prov:aws:account": "Account ID",
	"service:prov:aws:support:developer": "The Developer Support plan offers resources for customers testing or doing early development on AWS, as well as any customers who: • Want access to guidance and technical support; • Are exploring how to quickly put AWS to work; • Use AWS for non-production workloads or applications.",
	"service:prov:aws:support:business": "The Business Support plan offers resources for customers running production workloads on AWS as well as any customers who: • Run one or more applications in production environments; • Have multiple services activated, or use key services extensively; • Depend on their business solutions to be available, scalable, and secure.",
	"service:prov:aws:support:enterprise": "The Enterprise Support plan offers resources for customers running business & mission critical workloads on AWS, as well as any customers who want to: • Focus on proactive management to increase efficiency and availability; • Build and operate workloads following AWS best practices; • Leverage AWS expertise to support launches and migrations."
}, o = {
	"service:prov:aws:console": "Console AWS",
	"service:prov:aws:access-key-id": "Identifiant de clé d'accès",
	"service:prov:aws:secret-access-key": "Secret de clé d'accès",
	"service:prov:aws:account": "Numéro de compte",
	"service:prov:aws:support:developer": "Le plan Developer Support fournit des ressources aux clients en phase de test ou de développement initial sur AWS, ainsi qu'à tous les clients qui : • souhaitent accéder à des conseils et au support technique ; • explorent comment déployer rapidement AWS ; • utilisent AWS pour des charges de travail ou applications non productives.",
	"service:prov:aws:support:business": "Le plan Business Support fournit des ressources aux clients exécutant des charges de production sur AWS, ainsi qu'à tous les clients qui : • exécutent une ou plusieurs applications en environnement de production ; • ont plusieurs services activés ou utilisent intensivement des services clés ; • dépendent de la disponibilité, de l'évolutivité et de la sécurité de leurs solutions métier.",
	"service:prov:aws:support:enterprise": "Le plan Enterprise Support fournit des ressources aux clients exécutant des charges critiques sur AWS, ainsi qu'à tous les clients souhaitant : • se concentrer sur une gestion proactive pour gagner en efficacité et en disponibilité ; • construire et exploiter leurs charges selon les bonnes pratiques AWS ; • s'appuyer sur l'expertise AWS pour leurs lancements et migrations."
};
//#endregion
//#region src/service.js
function s(e) {
	return e?.parameters?.["service:prov:aws:account"] ?? null;
}
var c = {
	renderFeatures(t) {
		let { t: a } = r(), o = s(t);
		return o ? [i(e, {
			icon: !0,
			size: "small",
			variant: "text",
			href: `https://${encodeURIComponent(o)}.signin.aws.amazon.com/console`,
			target: "_blank",
			rel: "noopener",
			title: a("service:prov:aws:console")
		}, () => i(n, { size: "small" }, () => "mdi-aws"))] : [];
	},
	renderDetailsKey(e) {
		let { t: a } = r(), o = s(e);
		return o ? i(t, {
			size: "x-small",
			variant: "tonal",
			class: "mr-1",
			title: a("service:prov:aws:account")
		}, () => [
			i(n, {
				start: !0,
				size: "x-small"
			}, () => "mdi-aws"),
			" ",
			String(o)
		]) : null;
	},
	dashboardLink() {
		return "https://console.aws.amazon.com/cloudwatch/home#dashboards:";
	}
}, l = {
	renderFeatures: c.renderFeatures,
	renderDetailsKey: c.renderDetailsKey,
	dashboardLink: c.dashboardLink
}, u = {
	id: "prov-aws",
	label: "AWS",
	requires: ["prov"],
	install() {
		let e = r();
		e.merge(a, "en"), e.merge(o, "fr");
	},
	feature(e, ...t) {
		let n = l[e];
		if (!n) throw Error(`Plugin "prov-aws" has no feature "${e}"`);
		return n(...t);
	},
	service: c,
	meta: {
		icon: "mdi-aws",
		color: "orange-darken-3"
	}
};
//#endregion
export { u as default, c as service };
