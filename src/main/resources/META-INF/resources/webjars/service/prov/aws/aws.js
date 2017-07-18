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
				result += '<a href="https://'+ subscription.parameters['service:prov:aws:account'] + '.signin.aws.amazon.com/console"><i class="fa fa-home"></i></a>';
			}
			return result;
		},

		/**
		 * Render AWS console login page.
		 */
		renderDetailsKey: function (subscription) {
			return current.$super('generateCarousel')(subscription, [
				[
					'service:prov:aws:account', current.renderKey(subscription)
				]
			]);
		}
	};
	return current;
});
	