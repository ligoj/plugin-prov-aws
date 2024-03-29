# ${project_name}

```
This environment is in integration test mode (low cooldown, high refresh rate and fine grained monitoring) incurring additional costs.
```

All resources, including this dashboard have been generated with Terraform. You can change the resources and apply again the Terraform updates from the Ligoj subscription

[button:primary:Subscription](${ligoj_url}/#/home/project/${project}/${subscription})
[button:Project](${ligoj_url}/#/home/project/${project})
[button:Terraform files](${ligoj_url}/#/home/project/${project}/${subscription}/terraform.zip)

## Inventory

| Service                                                                                                                                                               | Name                                                      | Access         |
|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------|----------------|
| VPC                                                                                                                                                                   | [${vpc0}](/vpc/home?region=${region}#vpcs:filter=${vpc0}) |                |
| EC2                                                                                                                                                                   | [${spot0_name}](/ec2sp/v1/spot/home?region=${region}#)    | ${spot0_price} |
| *Generated by [Ligoj](https://ligoj.github.io/ligoj)/[plugin-prov](https://github.com/ligoj/plugin-prov)/[plugin-prov-aws](https://github.com/ligoj/plugin-prov-aws)* |                                                           |                |