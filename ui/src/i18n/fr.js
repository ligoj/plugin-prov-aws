// Traductions locales du plugin, fusionnées dans le store i18n de l'hôte
// au moment de l'installation. Clés à plat pour rester cohérent avec la
// convention de l'hôte.
export default {
  // Actions de ligne d'abonnement (renderFeatures).
  'service:prov:aws:console': 'Console AWS',

  // Identifiants d'API AWS (labels du formulaire d'abonnement).
  'service:prov:aws:access-key-id': "Identifiant de clé d'accès",
  'service:prov:aws:secret-access-key': "Secret de clé d'accès",
  'service:prov:aws:account': 'Numéro de compte',

  // Descriptions des plans de support AWS. Texte brut — le résolveur i18n
  // de l'hôte renvoie le message tel quel, donc on évite le HTML.
  'service:prov:aws:support:developer':
    "Le plan Developer Support fournit des ressources aux clients en phase de test ou de développement initial sur AWS, ainsi qu'à tous les clients qui : • souhaitent accéder à des conseils et au support technique ; • explorent comment déployer rapidement AWS ; • utilisent AWS pour des charges de travail ou applications non productives.",
  'service:prov:aws:support:business':
    "Le plan Business Support fournit des ressources aux clients exécutant des charges de production sur AWS, ainsi qu'à tous les clients qui : • exécutent une ou plusieurs applications en environnement de production ; • ont plusieurs services activés ou utilisent intensivement des services clés ; • dépendent de la disponibilité, de l'évolutivité et de la sécurité de leurs solutions métier.",
  'service:prov:aws:support:enterprise':
    "Le plan Enterprise Support fournit des ressources aux clients exécutant des charges critiques sur AWS, ainsi qu'à tous les clients souhaitant : • se concentrer sur une gestion proactive pour gagner en efficacité et en disponibilité ; • construire et exploiter leurs charges selon les bonnes pratiques AWS ; • s'appuyer sur l'expertise AWS pour leurs lancements et migrations.",
}
