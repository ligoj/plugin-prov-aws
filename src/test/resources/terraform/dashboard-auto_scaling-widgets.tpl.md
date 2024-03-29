# ${project_name}

```
This environment is in integration test mode (low cooldown, high refresh rate and fine grained monitoring) incurring additional costs.
```

All resources, including this dashboard have been generated with Terraform. You can change the resources and apply again the Terraform updates from the Ligoj subscription

[button:primary:Subscription](${ligoj_url}/#/home/project/${project}/${subscription})
[button:Project](${ligoj_url}/#/home/project/${project})
[button:Terraform files](${ligoj_url}/#/home/project/${project}/${subscription}/terraform.zip)

## Inventory

| Service                                                                                                                                                               | Name                                                                                             | Access                     |
|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------|----------------------------|
| VPC                                                                                                                                                                   | [${vpc0}](/vpc/home?region=${region}#vpcs:filter=${vpc0})                                        |                            |
| ALB                                                                                                                                                                   | [${alb0_name}](/ec2/v2/home?region=${region}#LoadBalancers:search=${alb0_dns})                   | [http](http://${alb0_dns}) |
| ALB                                                                                                                                                                   | [${alb1_name}](/ec2/v2/home?region=${region}#LoadBalancers:search=${alb1_dns})                   | [http](http://${alb1_dns}) |
| EC2/AS                                                                                                                                                                | [${asg0_name}](/ec2/autoscaling/home?region=${region}#AutoScalingGroups:id=${asg0};view=details) |                            |
| EC2/AS                                                                                                                                                                | [${asg1_name}](/ec2/autoscaling/home?region=${region}#AutoScalingGroups:id=${asg1};view=details) |                            |
| *Generated by [Ligoj](https://ligoj.github.io/ligoj)/[plugin-prov](https://github.com/ligoj/plugin-prov)/[plugin-prov-aws](https://github.com/ligoj/plugin-prov-aws)* |                                                                                                  |                            |