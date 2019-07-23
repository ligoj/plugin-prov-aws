/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
define(function () {
	var current = {

		/**
		 * Render Account identifier.
		 */
		renderKey: function (subscription) {
			return current.$super('renderKey')(subscription, 'service:prov:aws:account');
		},

		/**
		 * Render AWS console.
		 */
		renderFeatures: function (subscription) {
			var result = '';
			if (subscription.parameters && subscription.parameters['service:prov:aws:account']) {
				// Add console login page
				result += current.$super('renderServiceLink')('home', 'https://' + subscription.parameters['service:prov:aws:account'] + '.signin.aws.amazon.com/console', 'service:prov:aws:console', null, ' target="_blank"');
			}
			return result;
		},

		/**
		 * Render AWS account.
		 */
		renderDetailsKeyCallback: function (subscription) {
			current.$super('generateCarousel')(subscription, [
				[
					'service:prov:aws:account', current.renderKey(subscription)
				]
			], 0, 1);
		},
		
		dashboardLink: function(model) {
			return 'https://console.aws.amazon.com/cloudwatch/home#dashboards:';
		}
	};
	return current;
});
