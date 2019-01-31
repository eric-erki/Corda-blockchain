package net.corda.node.services.persistence

import net.corda.core.node.services.AttachmentId
import net.corda.core.node.services.AttachmentStorage
import net.corda.nodeapi.exceptions.DuplicateAttachmentException
import java.io.InputStream

interface AttachmentStorageInternal : AttachmentStorage {
    /**
     * This is the same as [importAttachment] expect there are no checks done on the uploader field. This API is internal
     * and is only for the node.
     */
    fun privilegedImportAttachment(jar: InputStream, uploader: String, filename: String?): AttachmentId

    /**
     * Same as [privilegedImportAttachment], but for Contract jars.
     */
    fun privilegedImportContractAttachment(jar: InputStream, uploader: String, filename: String?, contractClassNames: List<String>): AttachmentId

    /**
     * Similar to above but returns existing [AttachmentId] instead of throwing [DuplicateAttachmentException]
     */
    fun privilegedImportOrGetAttachment(jar: InputStream, uploader: String, filename: String?): AttachmentId

    /**
     * Similar to above but returns existing [AttachmentId] instead of throwing [DuplicateAttachmentException]
     */
    fun privilegedImportOrGetContractAttachment(jar: InputStream, uploader: String, filename: String?, contractClassNames: List<String>): AttachmentId
}
