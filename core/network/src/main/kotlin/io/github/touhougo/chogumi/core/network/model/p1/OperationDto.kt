package io.github.touhougo.chogumi.core.network.model.p1

import io.github.touhougo.chogumi.core.network.model.PatchField
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** p1 分页两字段均必需，不能复用 v0 的可省略分页。 */
@Serializable
data class PagedDto<T>(val data: List<T>, val total: Int)
@Serializable
data class CreateBlogRequestDto(val title: String, val content: String, val tags: List<String>? = null, val `public`: Boolean? = null, val subjectIDs: List<Int>? = null, val turnstileToken: String)
@Serializable
data class CreateTopicRequestDto(val title: String, val content: String, val turnstileToken: String)
@Serializable
data class CreateSubjectCommentRequestDto(val comment: String, val type: Int? = null, val rate: Int? = null, val turnstileToken: String)
@Serializable
data class FriendListDto(val friendlist: List<Int>)
@Serializable
data class BlockListDto(val blocklist: List<Int>)
@Serializable
data class ClearNotifyRequestDto(val id: List<Int>? = null)
@Serializable
data class MessageDto(val message: String)
@Serializable
data class DebugResponseDto(val requestID: String)
@Serializable
data class PasskeyCredentialHintDto(val credentialId: String, val transports: List<String>? = null)
@Serializable
data class PasskeyLoginOptionsRequestDto(val credentials: List<PasskeyCredentialHintDto>? = null)
@Serializable
data class PasskeyLoginOptionsResponseDto(val options: PasskeyAuthenticationOptionsDto, val challenge: String, val rpId: String)
/** 服务端 generatePasskeyAuthenticationOptions 的 WebAuthn JSON 选项，不用 Any 吞掉已知字段。 */
@Serializable
data class PasskeyAuthenticationOptionsDto(
    val challenge: String,
    val rpId: String? = null,
    val timeout: Long? = null,
    val allowCredentials: List<PasskeyAllowedCredentialDto>? = null,
    val userVerification: String? = null,
    val extensions: Map<String, JsonElement>? = null,
)
@Serializable
data class PasskeyAllowedCredentialDto(val id: String, val type: String, val transports: List<String>? = null)
@Serializable
data class PasskeyLoginVerifyRequestDto(val challenge: String, val credential: PasskeyAuthenticationResponseDto)
@Serializable
data class PasskeyAuthenticationResponseDto(val id: String, val rawId: String, val type: String, val response: PasskeyAuthenticatorResponseDto, val clientExtensionResults: Map<String, JsonElement>)
@Serializable
data class PasskeyAuthenticatorResponseDto(val clientDataJSON: String, val authenticatorData: String, val signature: String, val userHandle: String? = null)
@Serializable
data class PrivacyDto(val settings: PrivacySettingsDto, val preferences: PrivacyPreferencesDto)
@Serializable
data class PrivacySettingsDto(val privateMessage: String, val timelineReply: String, val timelineCollectReply: String, val follow: String, val mentionNotification: String, val commentNotification: String, val friendNotification: String)
@Serializable
data class PrivacyPreferencesDto(val showNsfwSubject: Boolean, val canSetNsfwSubject: Boolean, val allowNsfw: Boolean)
@Serializable
data class PrivacyPatchDto(val settings: PrivacySettingsPatchDto? = null, val preferences: PrivacyPreferencesPatchDto? = null)
@Serializable
data class PrivacySettingsPatchDto(
    val privateMessage: String? = null,
    val timelineReply: String? = null,
    val timelineCollectReply: String? = null,
    val follow: String? = null,
    val mentionNotification: String? = null,
    val commentNotification: String? = null,
    val friendNotification: String? = null,
)
@Serializable
data class PrivacyPreferencesPatchDto(val showNsfwSubject: Boolean? = null)
@Serializable
data class SubjectCoversDto(val current: CurrentCoverDto? = null, val covers: List<SubjectCoverDto>)
@Serializable
data class CurrentCoverDto(val thumbnail: String, val raw: String, val id: Int)
@Serializable
data class SubjectCoverDto(val id: Int, val thumbnail: String, val raw: String, val creator: P1UserDto, val voted: Boolean)
@Serializable
data class SubjectLockRequestDto(val subjectID: Int, val reason: String)
/** 乐观并发比较明确允许 null；Absent、Value(null)、Value(值) 三态互不混淆。 */
@Serializable
data class ExpectedSubjectRevisionDto(
    val name: PatchField<String?> = PatchField.Absent,
    val infobox: PatchField<String?> = PatchField.Absent,
    val platform: PatchField<Int?> = PatchField.Absent,
    val summary: PatchField<String?> = PatchField.Absent,
    val metaTags: PatchField<List<String>?> = PatchField.Absent,
)
@Serializable
data class SubjectPutRequestDto(val commitMessage: String, val expectedRevision: ExpectedSubjectRevisionDto? = null, val subject: SubjectEditDto)
@Serializable
data class SubjectPatchRequestDto(val commitMessage: String, val expectedRevision: ExpectedSubjectRevisionDto? = null, val subject: SubjectPatchDto, val authorID: Int? = null)
@Serializable
data class SubjectPatchDto(
    val name: String? = null,
    val infobox: String? = null,
    val platform: Int? = null,
    val series: Boolean? = null,
    val nsfw: Boolean? = null,
    val date: String? = null,
    val metaTags: List<String>? = null,
    val summary: String? = null,
)
@Serializable
data class CreatedSubjectDto(val subjectID: Double)
@Serializable
data class CreatedEpisodesDto(val episodeIDs: List<Int>)
@Serializable
data class MonoPatchDto(val name: String? = null, val infobox: String? = null, val summary: String? = null)
@Serializable
data class CharacterPatchRequestDto(val commitMessage: String, val expectedRevision: MonoPatchDto, val character: MonoPatchDto, val authorID: Int? = null)
@Serializable
data class CharacterCreateRequestDto(val character: CharacterCreateDto, val authorID: Int? = null)
@Serializable
data class CreatedCharacterDto(val characterID: Int)
@Serializable
data class PortraitUploadRequestDto(val img: String, val authorID: Int? = null)
@Serializable
data class PortraitUploadResponseDto(val img: String)
@Serializable
data class PersonPatchDto(val name: String? = null, val infobox: String? = null, val summary: String? = null, val profession: ProfessionDto? = null)
@Serializable
data class PersonPatchRequestDto(val commitMessage: String, val expectedRevision: MonoPatchDto, val person: PersonPatchDto, val authorID: Int? = null)
@Serializable
data class PersonCreateRequestDto(val person: PersonCreateDto, val authorID: Int? = null)
@Serializable
data class CreatedPersonDto(val personID: Int)
@Serializable
data class EpisodePatchDto(
    val subjectID: Int? = null,
    val name: String? = null,
    val nameCN: String? = null,
    val type: Int? = null,
    val ep: Double? = null,
    val disc: Double? = null,
    val duration: String? = null,
    val date: String? = null,
    val summary: String? = null,
)
@Serializable
data class EpisodePatchRequestDto(val commitMessage: String, val episode: EpisodePatchDto, val expectedRevision: ExpectedEpisodeRevisionDto? = null, val authorID: Int? = null)
@Serializable
data class RecentWikiItemDto(val id: Int, val createdAt: Long)
