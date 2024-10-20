/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2016 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.contrib.parking.parkingsearch.manager;

import com.google.inject.Inject;
import org.apache.commons.lang3.mutable.MutableLong;
import org.apache.logging.log4j.LogManager;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.parking.parkingsearch.ParkingUtils;
import org.matsim.contrib.parking.parkingsearch.sim.ParkingSearchConfigGroup;
import org.matsim.core.utils.misc.Time;
import org.matsim.facilities.ActivityFacility;
import org.matsim.vehicles.Vehicle;

import java.util.*;
import java.util.Map.Entry;

/**
 * @author  jbischoff, schlenther
 *
 */
public class FacilityBasedParkingManager implements ParkingSearchManager {

	protected Map<Id<Link>, Integer> capacity = new HashMap<>();
	protected Map<Id<ActivityFacility>, MutableLong> occupation = new HashMap<>();
	protected Map<Id<ActivityFacility>, MutableLong> reservationsRequests = new HashMap<>();
	protected Map<Id<ActivityFacility>, MutableLong> rejectedReservations = new HashMap<>();
	protected Map<Id<ActivityFacility>, MutableLong> numberOfParkedVehicles = new HashMap<>();
	protected TreeMap<Integer, MutableLong> rejectedReservationsByTime = new TreeMap<>();
	protected TreeMap<Integer, MutableLong> foundParkingByTime = new TreeMap<>();
	protected TreeMap<Integer, MutableLong> unparkByTime = new TreeMap<>();
	protected Map<Id<ActivityFacility>, ActivityFacility> parkingFacilities;
	protected Map<Id<Vehicle>, Id<ActivityFacility>> parkingLocations = new HashMap<>();
	protected Map<Id<Vehicle>, Id<ActivityFacility>> parkingReservation = new HashMap<>();
	protected Map<Id<Vehicle>, Id<Link>> parkingLocationsOutsideFacilities = new HashMap<>();
	protected Map<Id<Link>, Set<Id<ActivityFacility>>> facilitiesPerLink = new HashMap<>();
    protected Network network;
	protected boolean canParkOnlyAtFacilities;
	private final int maxSlotIndex;
	private final int maxTime;
	private final int timeBinSize;
	private final int startTime;

	@Inject
	public FacilityBasedParkingManager(Scenario scenario) {
		ParkingSearchConfigGroup psConfigGroup = (ParkingSearchConfigGroup) scenario.getConfig().getModules().get(ParkingSearchConfigGroup.GROUP_NAME);
		canParkOnlyAtFacilities = psConfigGroup.getCanParkOnlyAtFacilities();
		this.network = scenario.getNetwork();
		parkingFacilities = scenario.getActivityFacilities()
				.getFacilitiesForActivityType(ParkingUtils.PARKACTIVITYTYPE);
		LogManager.getLogger(getClass()).info(parkingFacilities.toString());
		this.timeBinSize = 15*60;
		this.maxTime =  24 * 3600 -1;
		this.maxSlotIndex = (this.maxTime / this.timeBinSize) + 1;
		this.startTime = 9 * 3600;

		for (ActivityFacility fac : this.parkingFacilities.values()) {
			Id<Link> linkId = fac.getLinkId();
			Set<Id<ActivityFacility>> parkingOnLink = new HashSet<>();
			if (this.facilitiesPerLink.containsKey(linkId)) {
				parkingOnLink = this.facilitiesPerLink.get(linkId);
			}
			parkingOnLink.add(fac.getId());
			this.facilitiesPerLink.put(linkId, parkingOnLink);
			this.occupation.put(fac.getId(), new MutableLong(0));
			this.reservationsRequests.put(fac.getId(), new MutableLong(0));
			this.rejectedReservations.put(fac.getId(), new MutableLong(0));
			this.numberOfParkedVehicles.put(fac.getId(), new MutableLong(0));
		}
		int slotIndex = getTimeSlotIndex(startTime);
		while (slotIndex <= maxSlotIndex) {
			rejectedReservationsByTime.put(slotIndex * timeBinSize, new MutableLong(0));
			foundParkingByTime.put(slotIndex * timeBinSize, new MutableLong(0));
			unparkByTime.put(slotIndex * timeBinSize, new MutableLong(0));
			slotIndex++;
		}
	}

	@Override
	public boolean reserveSpaceIfVehicleCanParkHere(Id<Vehicle> vehicleId, Id<Link> linkId) {
		boolean canPark = false;

		if (linkIdHasAvailableParkingForVehicle(linkId, vehicleId)) {
			canPark = true;
			// LogManager.getLogger(getClass()).info("veh: "+vehicleId+" link
			// "+linkId + " can park "+canPark);
		}

		return canPark;
	}

	private boolean linkIdHasAvailableParkingForVehicle(Id<Link> linkId, Id<Vehicle> vid) {
		// LogManager.getLogger(getClass()).info("link "+linkId+" vehicle "+vid);
		if (!this.facilitiesPerLink.containsKey(linkId) && !canParkOnlyAtFacilities) {
			// this implies: If no parking facility is present, we suppose that
			// we can park freely (i.e. the matsim standard approach)
			// it also means: a link without any parking spaces should have a
			// parking facility with 0 capacity.
			// LogManager.getLogger(getClass()).info("link not listed as parking
			// space, we will say yes "+linkId);

			return true;
		} else if (!this.facilitiesPerLink.containsKey(linkId)) {
			return false;
		}
		Set<Id<ActivityFacility>> parkingFacilitiesAtLink = this.facilitiesPerLink.get(linkId);
		for (Id<ActivityFacility> fac : parkingFacilitiesAtLink) {
			double cap = this.parkingFacilities.get(fac).getActivityOptions().get(ParkingUtils.PARKACTIVITYTYPE)
					.getCapacity();
			this.reservationsRequests.get(fac).increment();
			if (this.occupation.get(fac).doubleValue() < cap) {
				// LogManager.getLogger(getClass()).info("occ:
				// "+this.occupation.get(fac).toString()+" cap: "+cap);
				this.occupation.get(fac).increment();
				this.parkingReservation.put(vid, fac);

				return true;
			}
			this.rejectedReservations.get(fac).increment();
		}
		return false;
	}

	@Override
	public Id<Link> getVehicleParkingLocation(Id<Vehicle> vehicleId) {
		if (this.parkingLocations.containsKey(vehicleId)) {
			return this.parkingFacilities.get(this.parkingLocations.get(vehicleId)).getLinkId();
		} else return this.parkingLocationsOutsideFacilities.getOrDefault(vehicleId, null);
	}

	@Override
	public boolean parkVehicleHere(Id<Vehicle> vehicleId, Id<Link> linkId, double time) {
        return parkVehicleAtLink(vehicleId, linkId, time);
	}

	protected boolean parkVehicleAtLink(Id<Vehicle> vehicleId, Id<Link> linkId, double time) {
		Set<Id<ActivityFacility>> parkingFacilitiesAtLink = this.facilitiesPerLink.get(linkId);
		if (parkingFacilitiesAtLink == null) {
			this.parkingLocationsOutsideFacilities.put(vehicleId, linkId);
			return true;
		} else {
			Id<ActivityFacility> fac = this.parkingReservation.remove(vehicleId);
			if (fac != null) {
				this.parkingLocations.put(vehicleId, fac);
				this.numberOfParkedVehicles.get(fac).increment();
				foundParkingByTime.get(getTimeSlotIndex(time) * timeBinSize).increment();
				return true;
			} else {
				throw new RuntimeException("no parking reservation found for vehicle " + vehicleId.toString()
						+ "arrival on link " + linkId + " with parking restriction");
			}
		}
	}

	@Override
	public boolean unParkVehicleHere(Id<Vehicle> vehicleId, Id<Link> linkId, double time) {
		if (!this.parkingLocations.containsKey(vehicleId)) {
			this.parkingLocationsOutsideFacilities.remove(vehicleId);
			return true;

			// we assume the person parks somewhere else
		} else {
			Id<ActivityFacility> fac = this.parkingLocations.remove(vehicleId);
			this.occupation.get(fac).decrement();
			unparkByTime.get(getTimeSlotIndex(time) * timeBinSize).increment();
			return true;
		}
	}

	@Override
	public List<String> produceStatistics() {
		List<String> stats = new ArrayList<>();
		for (Entry<Id<ActivityFacility>, MutableLong> e : this.occupation.entrySet()) {
			Id<Link> linkId = this.parkingFacilities.get(e.getKey()).getLinkId();
			double capacity = this.parkingFacilities.get(e.getKey()).getActivityOptions()
					.get(ParkingUtils.PARKACTIVITYTYPE).getCapacity();
			double x = this.parkingFacilities.get(e.getKey()).getCoord().getX();
			double y = this.parkingFacilities.get(e.getKey()).getCoord().getY();

			String s = linkId.toString() + ";" + x + ";" + y + ";" + e.getKey().toString() + ";" + capacity + ";" + e.getValue().toString() + ";" + this.reservationsRequests.get(e.getKey()).toString() + ";" + this.numberOfParkedVehicles.get(e.getKey()).toString() + ";" + this.rejectedReservations.get(e.getKey()).toString();
			stats.add(s);
		}
		return stats;
	}

	public List<String> produceTimestepsStatistics() {
		List<String> stats = new ArrayList<>();
		for (int time : rejectedReservationsByTime.keySet()) {

			String s = Time.writeTime(time, Time.TIMEFORMAT_HHMM) + ";" + rejectedReservationsByTime.get(time) + ";" + foundParkingByTime.get(
					time) + ";" + unparkByTime.get(time);
			stats.add(s);

		}
		return stats;
	}
	public double getNrOfAllParkingSpacesOnLink (Id<Link> linkId){
		double allSpaces = 0;
		Set<Id<ActivityFacility>> parkingFacilitiesAtLink = this.facilitiesPerLink.get(linkId);
		if (!(parkingFacilitiesAtLink == null)) {
			for (Id<ActivityFacility> fac : parkingFacilitiesAtLink){
				allSpaces += this.parkingFacilities.get(fac).getActivityOptions().get(ParkingUtils.PARKACTIVITYTYPE).getCapacity();
			}
		}
		return allSpaces;
	}

	public double getNrOfFreeParkingSpacesOnLink (Id<Link> linkId){
		double allFreeSpaces = 0;
		Set<Id<ActivityFacility>> parkingFacilitiesAtLink = this.facilitiesPerLink.get(linkId);
		if (parkingFacilitiesAtLink == null) {
			return 0;
		} else {
			for (Id<ActivityFacility> fac : parkingFacilitiesAtLink){
				int cap = (int) this.parkingFacilities.get(fac).getActivityOptions().get(ParkingUtils.PARKACTIVITYTYPE).getCapacity();
				allFreeSpaces += (cap - this.occupation.get(fac).intValue());
			}
		}
		return allFreeSpaces;
	}

	public Map<Id<ActivityFacility>, ActivityFacility> getParkingFacilities() {
		return this.parkingFacilities;
	}

	public void registerRejectedReservation(double now){
		rejectedReservationsByTime.get(getTimeSlotIndex(now) * timeBinSize).increment();
	}

	public TreeSet<Integer> getTimeSteps(){
		TreeSet<Integer> timeSteps = new TreeSet<>();
		int slotIndex = 0;
		while (slotIndex <= maxSlotIndex) {
			timeSteps.add(slotIndex * timeBinSize);
			slotIndex++;
		}
		return timeSteps;
	}

	private int getTimeSlotIndex(final double time) {
		if (time > this.maxTime) {
			return this.maxSlotIndex;
		}
		return ((int)time / this.timeBinSize);
	}

	@Override
	public void reset(int iteration) {
		for (Id<ActivityFacility> fac : this.rejectedReservations.keySet()) {
			this.rejectedReservations.get(fac).setValue(0);
			this.reservationsRequests.get(fac).setValue(0);
			this.numberOfParkedVehicles.get(fac).setValue(0);
		}
	}
}
