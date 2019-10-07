/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.model;

import java.io.Serializable;

import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import org.hibernate.validator.constraints.Range;
import org.ligoj.app.model.AbstractLongTaskNode;

import lombok.Getter;
import lombok.Setter;

/**
 * Discovery import status.
 */
@Getter
@Setter
@Entity
@Table(name = "LIGOJ_PROV_AWS_DISCOVERY", uniqueConstraints = @UniqueConstraint(columnNames = "locked"))
public class DiscoveryStatus extends AbstractLongTaskNode implements Serializable {

	/**
	 * SID
	 */
	private static final long serialVersionUID = 1L;

	/**
	 * The subscription identifier requesting this task.
	 */
	private int subscription;

	/**
	 * Discovery export task identifier
	 */
	private String exportTask;

	/**
	 * S3 export URL.
	 */
	private String exportUrl;

	/**
	 * Athena export task.
	 */
	private String athenaTask;
	/**
	 * Athena result URL.
	 */
	private String athenaUrl;
	/**
	 * Percent progress
	 */
	@Range(min = 0, max = 100)
	private int progress;

	/**
	 * Current step.
	 */
	private DiscoveryStep step;

}
