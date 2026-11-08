package com.cosmod.launcherglass.xposed

/** Missing transient tags are not a rebind. Only a known different database item is. */
internal data class FlightSourceIdentity(val id:Long,val user:Any?) {
    fun replacedBy(current:FlightSourceIdentity?)=current!=null && this!=current
}
