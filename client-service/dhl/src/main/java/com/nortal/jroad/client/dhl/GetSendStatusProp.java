package com.nortal.jroad.client.dhl;

/**
 * Created by Vassili on 5/22/2017.
 */
public interface GetSendStatusProp {

	enum Edastus {
		REGNR("regnr"),
		ISIKUKOOD("isikukood"),
		STAATUS("staatus"),
		LOETUD("loetud");

		private final String name;

		private Edastus(String name) {
			this.name = name;
		}

		public String getName() {
			return name;
		}
	}

}
