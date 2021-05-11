package com.nortal.jroad.client.dhl;

import com.nortal.jroad.client.dhl.types.ee.riik.schemas.deccontainer.vers21.DecContainerDocument.DecContainer;
import com.nortal.jroad.client.dhl.types.ee.riik.schemas.dhl.DhlDokumentType;

public class VersionedDhlDocument {

	private DhlDokumentType documentV1;
	private DecContainer documentV2;

	public VersionedDhlDocument(DhlDokumentType documentV1) {
		this.documentV1 = documentV1;
	}

	public VersionedDhlDocument(DecContainer documentV2) {
		this.documentV2 = documentV2;
	}

	public DhlDokumentType getDocumentV1() {
		return documentV1;
	}

	public DecContainer getDocumentV2() {
		return documentV2;
	}

	public boolean isV1() {
		return documentV1 != null;
	}

	public boolean isV2() {
		return documentV2 != null;
	}
}