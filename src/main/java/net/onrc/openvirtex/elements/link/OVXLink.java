/*******************************************************************************
 * Copyright (c) 2013 Open Networking Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 ******************************************************************************/

package net.onrc.openvirtex.elements.link;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.onrc.openvirtex.api.service.handlers.TenantHandler;
import net.onrc.openvirtex.db.DBManager;
import net.onrc.openvirtex.elements.Mappable;
import net.onrc.openvirtex.elements.OVXMap;
import net.onrc.openvirtex.elements.address.IPMapper;
import net.onrc.openvirtex.elements.datapath.OVXSwitch;
import net.onrc.openvirtex.elements.port.OVXPort;
import net.onrc.openvirtex.elements.port.PhysicalPort;
import net.onrc.openvirtex.exceptions.LinkMappingException;
import net.onrc.openvirtex.exceptions.NetworkMappingException;
import net.onrc.openvirtex.messages.OVXFlowMod;
import net.onrc.openvirtex.messages.OVXPacketOut;
import net.onrc.openvirtex.messages.actions.OVXActionOutput;
import net.onrc.openvirtex.packet.Ethernet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/**
 * The Class OVXLink.
 * 
 */
public class OVXLink extends Link<OVXPort, OVXSwitch> {
	Logger                log = LogManager.getLogger(OVXLink.class.getName());

	/** The link id. */


	@SerializedName("linkId")
	@Expose
	private final Integer linkId;

	/** The tenant id. */
	@SerializedName("tenantId")
	@Expose
	private final Integer tenantId;

	private final byte priority;

	private Mappable      map = null;


	/**
	 * Instantiates a new virtual link.
	 * 
	 * @param linkId
	 *            link id
	 * @param tenantId
	 *            tenant id
	 * @param srcPort
	 *            virtual source port
	 * @param dstPort
	 *            virtual destination port
	 * @param priority 
	 */
	public OVXLink(final Integer linkId, final Integer tenantId,
			final OVXPort srcPort, final OVXPort dstPort, byte priority) {
		super(srcPort, dstPort);
		this.linkId = linkId;
		this.tenantId = tenantId;
		this.priority = priority;
		srcPort.setOutLink(this);
		dstPort.setInLink(this);
		this.map = OVXMap.getInstance();
	}

	/**
	 * Gets the link id.
	 * 
	 * @return the link id
	 */
	public Integer getLinkId() {
		return this.linkId;
	}

	/**
	 * Gets the tenant id.
	 * 
	 * @return the tenant id
	 */
	public Integer getTenantId() {
		return this.tenantId;
	}

	public byte getPriority() {
		return priority;
	}
    
	/**
	 * Register mapping between virtual link and physical path
	 * 
	 * @param physicalLinks
	 */
	public void register(final List<PhysicalLink> physicalLinks) {
		this.srcPort.getParentSwitch().getMap().addLinks(physicalLinks, this);
		this.srcPort.getPhysicalPort().removeOVXPort(this.srcPort);
		this.srcPort.getPhysicalPort().setOVXPort(this.srcPort);
		DBManager.getInstance().save(this);
	}

	@Override
	public void unregister() {
		try {
			this.tearDown();
			final Mappable map = this.srcPort.getParentSwitch().getMap();
			map.removeVirtualLink(this);
			map.getVirtualNetwork(this.tenantId).removeLink(this);
		} catch (NetworkMappingException e) {
			log.warn("[unregister()]: could not remove this link from map \n{}", e.getMessage());
		}
	}    

	/**
	 * Disables this OVXLink by disabling its endpoints. 
	 */
	public void tearDown() {
		this.srcPort.tearDown();
		this.dstPort.tearDown();
	}

	@Override
	public Map<String, Object> getDBIndex() {
		Map<String, Object> index = new HashMap<String, Object>();
		index.put(TenantHandler.TENANT, this.tenantId);
		return index;
	}

	@Override
	public String getDBKey() {
		return Link.DB_KEY;
	}

	@Override
	public String getDBName() {
		return DBManager.DB_VNET;
	}

	@Override
	public Map<String, Object> getDBObject() {
		try {
			Map<String, Object> dbObject = super.getDBObject(); 
			dbObject.put(TenantHandler.LINK, this.linkId);
			dbObject.put(TenantHandler.PRIORITY, this.priority);
			// Build path list
			List<PhysicalLink> links = map.getPhysicalLinks(this);
			List<Map<String, Object>> path = new ArrayList<Map<String, Object>>();
			for (PhysicalLink link: links) {
				// Physical link id's are meaningless when restarting OVX
				Map obj = link.getDBObject();
				obj.remove(TenantHandler.LINK);
				path.add(obj);
			}
			dbObject.put(TenantHandler.PATH, path);
			return dbObject;
		} catch (LinkMappingException e) {
			return null;
		}
	}

	/**
	 * Push the flow-mod to all the middle point of a virtual link
	 * 
	 * @param the
	 *            original flow mod
	 * @param the
	 *            flow identifier
	 * @param the
	 *            source switch
	 */
	public void generateLinkFMs(final OVXFlowMod fm, final Integer flowId) {
		/*
		 * Change the packet match:
		 * 1) change the fields where the virtual link info are stored
		 * 2) change the fields where the physical ips are stored
		 */
		final OVXLinkUtils lUtils = new OVXLinkUtils(this.tenantId,
				this.linkId, flowId);
		lUtils.rewriteMatch(fm.getMatch());
		long cookie = tenantId;
		fm.setCookie(cookie << 32);

		if (fm.getMatch().getDataLayerType() == Ethernet.TYPE_IPv4)
			IPMapper.rewriteMatch(this.tenantId, fm.getMatch());

		/*
		 * Get the list of physical links mapped to this virtual link,
		 * in REVERSE ORDER
		 */
		PhysicalPort inPort = null;
		PhysicalPort outPort = null;
		fm.setBufferId(OVXPacketOut.BUFFER_ID_NONE);

		List<PhysicalLink> plinks;
		try {
			final OVXLink reverseLink = this.map.getVirtualNetwork(this.tenantId)
					.getLink(this.dstPort, this.srcPort);
			plinks = this.map.getPhysicalLinks(reverseLink); 	
		} catch (LinkMappingException | NetworkMappingException e) {
			log.warn("No physical Links mapped to OVXLink? : {}", e);
			return;
		}
		for (final PhysicalLink phyLink : plinks) {
			if (outPort != null) {
				inPort = phyLink.getSrcPort();
				fm.getMatch().setInputPort(inPort.getPortNumber());
				fm.setLengthU(OVXFlowMod.MINIMUM_LENGTH
						+ OVXActionOutput.MINIMUM_LENGTH);
				fm.setActions(Arrays.asList((OFAction) new OFActionOutput(
						outPort.getPortNumber(), (short) 0xffff)));
				phyLink.getSrcPort().getParentSwitch()
				.sendMsg(fm, phyLink.getSrcPort().getParentSwitch());
				this.log.debug(
						"Sending virtual link intermediate fm to sw {}: {}",
						phyLink.getSrcPort().getParentSwitch().getName(), fm);

			}
			outPort = phyLink.getDstPort();
		}
		// TODO: With POX we need to put a timeout between this flows and the
		// first flowMod. Check how to solve
		try {
			Thread.sleep(5);
		} catch (final InterruptedException e) {
		}
	}

    /**
     * Tries to switch link to a backup path, and updates mappings to "correct" 
     * string of PhysicalLinks to use for this OVXLink.  
     * @param plink the failed PhysicalLink
     * @return true if successful
     */
    public boolean tryRecovery(PhysicalLink plink) {
	return false;
    }

}
