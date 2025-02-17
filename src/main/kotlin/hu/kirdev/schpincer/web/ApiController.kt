package hu.kirdev.schpincer.web

import hu.kirdev.schpincer.dto.ItemEntityDto
import hu.kirdev.schpincer.dto.ManualUserDetails
import hu.kirdev.schpincer.model.ItemCategory
import hu.kirdev.schpincer.model.ItemEntity
import hu.kirdev.schpincer.model.OpeningEntity
import hu.kirdev.schpincer.model.OrderStatus
import hu.kirdev.schpincer.service.*
import io.swagger.annotations.ApiOperation
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.lang.Integer.min
import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors
import javax.servlet.http.HttpServletRequest

@RestController
@RequestMapping("/api")
open class ApiController(
        private val openings: OpeningService,
        private val items: ItemService,
        private val users: UserService,
        private val orders: OrderService,
        private val timeService: TimeService,
        @Value("\${schpincer.api-tokens:}")
        apiTokensRaw: String,

        @Value("\${schpincer.api.base-url}")
        private val baseUrl: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val apiTokens = apiTokensRaw.split(Regex(", *"))

    @ApiOperation("Item info")
    @GetMapping("/item/{id}")
    @ResponseBody
    fun getItem(
            request: HttpServletRequest,
            @PathVariable id: Long,
            @RequestParam(defaultValue = "0") explicitOpening: Long
    ): ItemEntityDto? {
        val item = items.getOne(id)
        if (item == null || (!request.hasUser() && !item.visibleWithoutLogin))
            return null
        val loggedIn = request.hasUser() || request.isInInternalNetwork()
        val opening = if (explicitOpening != 0L) openings.getOne(explicitOpening) else openings.findNextOf(item.circle!!.id)
        return ItemEntityDto(item, opening, loggedIn, loggedIn && explicitOpening > 0)
    }

    @ApiOperation("List of items")
    @GetMapping("/items")
    @ResponseBody
    fun getAllItems(
            request: HttpServletRequest,
            @RequestParam(required = false) circle: Long?
    ): ResponseEntity<List<ItemEntityDto>> {
        val cache: MutableMap<Long, OpeningEntity?> = HashMap()
        val loggedIn = request.hasUser()
        if (circle != null) {
            val list = items.findAllByCircle(circle).stream()
                    .filter { it.visibleWithoutLogin || loggedIn }
                    .filter(ItemEntity::visible)
                    .map { item: ItemEntity ->
                        ItemEntityDto(item,
                                cache.computeIfAbsent(item.circle!!.id) { openings.findNextOf(it) },
                                loggedIn || request.isInInternalNetwork(),
                                false)
                    }
                    .collect(Collectors.toList())
            return ResponseEntity(list, HttpStatus.OK)
        }
        val list = items.findAll().stream()
                .filter { it.visibleWithoutLogin || loggedIn }
                .filter(ItemEntity::visibleInAll)
                .filter(ItemEntity::visible)
                .map { item: ItemEntity ->
                    ItemEntityDto(item,
                            cache.computeIfAbsent(item.circle!!.id) { openings.findNextOf(it) },
                            loggedIn || request.isInInternalNetwork(),
                            false)
                }
                .collect(Collectors.toList())
        return ResponseEntity(list, HttpStatus.OK)
    }

    @ApiOperation("List of items orderable right now")
    @GetMapping("/items/now")
    @ResponseBody
    fun getAllItemsToday(request: HttpServletRequest): ResponseEntity<List<ItemEntityDto>> {
        val loggedIn = request.hasUser()
        val cache: MutableMap<Long, OpeningEntity?> = HashMap()
        val list = items.findAllByOrderableNow().stream()
                .filter { it.visibleWithoutLogin || loggedIn }
                .filter(ItemEntity::visibleInAll)
                .filter(ItemEntity::visible)
                .map { item: ItemEntity ->
                    ItemEntityDto(item,
                            cache.computeIfAbsent(item.circle!!.id) { openings.findNextOf(it) },
                            loggedIn || request.isInInternalNetwork(),
                            false)
                }
                .collect(Collectors.toList())
        return ResponseEntity(list, HttpStatus.OK)
    }

    @ApiOperation("List of items orderable tomorrow")
    @GetMapping("/items/tomorrow")
    @ResponseBody
    fun getAllItemsTomorrow(request: HttpServletRequest): ResponseEntity<List<ItemEntityDto>> {
        val loggedIn = request.hasUser()
        val cache: MutableMap<Long, OpeningEntity?> = HashMap()
        val list = items.findAllByOrerableTomorrow().stream()
                .filter { it.visibleWithoutLogin || loggedIn }
                .filter(ItemEntity::visibleInAll)
                .filter(ItemEntity::visible)
                .map { item: ItemEntity ->
                    ItemEntityDto(item,
                            cache.computeIfAbsent(item.circle!!.id) { openings.findNextOf(it) },
                            loggedIn || request.isInInternalNetwork(),
                            false)
                }
                .collect(Collectors.toList())
        return ResponseEntity(list, HttpStatus.OK)
    }

    data class NewOrderRequest(var id: Long = -1,
                               var time: Int = -1,
                               var comment: String = "",
                               var count: Int = 1,
                               var detailsJson: String = "{}",
                               var manualOrderDetails: ManualUserDetails? = null
    )

    @ApiOperation("New order")
    @PostMapping("/order")
    @ResponseBody
    @Throws(Exception::class)
    fun newOrder(request: HttpServletRequest, @RequestBody requestBody: NewOrderRequest): ResponseEntity<String> {
        if (requestBody.id < 0 || requestBody.time < 0 || requestBody.detailsJson == "{}")
            return ResponseEntity(RESPONSE_INTERNAL_ERROR, HttpStatus.OK)
        val user = request.getUserIfPresent() ?: return responseOf("Error 403", HttpStatus.FORBIDDEN)
        return try {
            if (requestBody.manualOrderDetails != null) {
                log.info("{}:{} is making a manual order with details: {}, for {}",
                    user.name, user.uid, requestBody.detailsJson, requestBody.manualOrderDetails?.toString() ?: "null")
                orders.makeManualOrder(user, requestBody.id, requestBody.count, requestBody.time.toLong(),
                    requestBody.comment, requestBody.detailsJson, requestBody.manualOrderDetails!!)
            } else {
                orders.makeOrder(user, requestBody.id, requestBody.count, requestBody.time.toLong(),
                    requestBody.comment, requestBody.detailsJson)
            }
        } catch (e: FailedOrderException) {
            log.warn("Failed to make new order by '${request.getUserIfPresent()?.uid ?: "n/a"}' reason: ${e.response}")
            responseOf(e.response)
        }
    }

    data class RoomChangeRequest(var room: String = "")

    @ApiOperation("Set room code")
    @PostMapping("/user/room")
    @ResponseBody
    fun setRoom(request: HttpServletRequest, @RequestBody(required = true) requestBody: RoomChangeRequest): String {
        return try {
            request.session.setAttribute(USER_ENTITY_DTO_SESSION_ATTRIBUTE_NAME, users.setRoom(request.getUserId(), requestBody.room))
            "ACK"
        } catch (e: Exception) {
            "REJECT"
        }
    }

    data class DeleteRequestDto(var id: Long = 0)

    @ApiOperation("Delete order")
    @PostMapping("/order/delete")
    @ResponseBody
    fun deleteOrder(request: HttpServletRequest, @RequestBody(required = true) body: DeleteRequestDto): ResponseEntity<String> {
        val user = request.getUserIfPresent() ?: return responseOf("Error 403", HttpStatus.FORBIDDEN)
        return try {
            orders.cancelOrder(user, body.id)
        } catch (e: FailedOrderException) {
            log.warn("Failed to cancel order by '${request.getUserIfPresent()?.uid ?: "n/a"}' reason: ${e.response}")
            responseOf(e.response)
        }
    }

    data class ChangeRequestDto(var id: Long = 0, var room: String = "", var comment: String = "")

    @ApiOperation("Change order")
    @PostMapping("/order/change")
    @ResponseBody
    fun changeOrder(request: HttpServletRequest, @RequestBody(required = true) body: ChangeRequestDto): ResponseEntity<String> {
        val user = request.getUserIfPresent() ?: return responseOf("Error 403", HttpStatus.FORBIDDEN)
        return try {
            orders.changeOrder(user, body.id, body.room, body.comment)
        } catch (e: FailedOrderException) {
            log.warn("Failed to change order by '${request.getUserIfPresent()?.uid ?: "n/a"}' reason: ${e.response}")
            responseOf(e.response)
        }
    }

    @GetMapping("/version")
    @ResponseBody
    fun version(): String {
        return "Version: " + javaClass.getPackage().implementationVersion
    }

    @GetMapping("/time")
    @ResponseBody
    fun time(): String {
        return "Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z").format(System.currentTimeMillis())}\n" +
                "Timestamp: ${System.currentTimeMillis()}"
    }

    data class OpeningDetail(
            var name: String,
            var icon: String?,
            var feeling: String,
            var available: Int,
            var outOf: Int,
            var banner: String?,
            var day: String,
            var comment: String,
            var circleUrl: String,
            var circleColor: String
    )

    private val daysOfTheWeek = arrayOf("n/a", "Hétfő", "Kedd", "Szerda", "Csütörtök", "Péntek", "Szombat", "Vasárnap")

    @CrossOrigin(origins = ["*"])
    @GetMapping("/open/openings")
    @ResponseBody
    fun openingsApi(@RequestParam(required = false) token: String?): List<OpeningDetail> {
        if (token.isNullOrBlank() || !apiTokens.contains(token))
            return listOf(OpeningDetail("Invalid Token", null, "sad", 0, 0, null,
                    "", "Contact the administrator if you think this is a problem", "", ""))

        return openings.findNextWeek()
                .filter { it.circle != null }
                .filter { it.orderStart + it.compensationTime <= System.currentTimeMillis() }
                .map { openingEntity ->
                    OpeningDetail(
                        name = openingEntity.circle?.displayName ?: "n/a",
                        icon = openingEntity.circle?.logoUrl?.let { url -> baseUrl + url },
                        feeling = openingEntity.feeling ?: "",
                        available = calculateAvailable(openingEntity).coerceAtLeast(0),
                        outOf = openingEntity.maxOrder,
                        banner = openingEntity.prUrl.let { url -> baseUrl + url },
                        day = timeService.format(openingEntity.dateStart, "u")?.toInt().let { daysOfTheWeek[it ?: 0] },
                        comment = "${timeService.format(openingEntity.orderEnd, "u")?.toInt().let { daysOfTheWeek[it ?: 0] }} " +
                                "${timeService.format(openingEntity.orderEnd, "HH:mm")}-ig rendelhető",
                        circleUrl = openingEntity.circle?.alias?.let { alias -> baseUrl + "p/" + alias }
                            ?: (baseUrl + "p/" + (openingEntity.circle?.id ?: 0)),
                        circleColor = openingEntity.circle?.cssClassName ?: "none"
                    ) }
                .filter { it.available > 0 }
    }

    private fun calculateAvailable(openingEntity: OpeningEntity): Int {
        val orders = orders.findAllByOpening(openingEntity.id)
            .filter { it.status == OrderStatus.ACCEPTED }

        val maxOverall = openingEntity.maxOrder - orders.sumOf { it.count }
        val available = min(maxOverall, orders.groupBy { it.orderedItem?.category ?: 0 }
            .map { pair -> pair.key to pair.value.sumOf { it.count } }
            .map { pair ->
                when (ItemCategory.of(pair.first)) {
                    ItemCategory.DEFAULT -> maxOverall
                    ItemCategory.ALPHA -> openingEntity.maxAlpha - pair.second
                    ItemCategory.BETA -> openingEntity.maxBeta - pair.second
                    ItemCategory.GAMMA -> openingEntity.maxGamma - pair.second
                    ItemCategory.DELTA -> openingEntity.maxDelta - pair.second
                    ItemCategory.LAMBDA -> openingEntity.maxLambda - pair.second
                }
            }
            .maxOrNull() ?: maxOverall)
        return available
    }

    data class UpcomingOpeningDetail(
            var name: String,
            var orderStart: Long,
            var openingStart: Long,
            var icon: String?,
            var feeling: String,
            var available: Int,
            var outOf: Int,
            var banner: String?,
            var day: String,
            var comment: String,
            var circleUrl: String,
            var circleColor: String
    )

    @CrossOrigin(origins = ["*"])
    @GetMapping("/open/upcoming-openings")
    @ResponseBody
    fun upcomingOpeningsApiDeprecated(@RequestParam(required = false) token: String?): List<UpcomingOpeningDetail> {
        return listOf(UpcomingOpeningDetail("This feature is deprecated", 0, 0,null,
            "sad", 0, 0, null, "",
            "Contact the administrator if you think this is a problem", "", ""))
    }

    fun upcomingOpeningsApi(@RequestParam(required = false) token: String?): List<UpcomingOpeningDetail> {
        if (token.isNullOrBlank() || !apiTokens.contains(token))
            return listOf(UpcomingOpeningDetail("Invalid Token", 0, 0,null,
                    "sad", 0, 0, null, "",
                    "Contact the administrator if you think this is a problem", "", ""))

        return openings.findNextWeek()
                .filter { it.circle != null }
                .map { openingEntity ->
                    UpcomingOpeningDetail(
                        name = openingEntity.circle?.displayName ?: "n/a",
                        orderStart = openingEntity.orderStart,
                        openingStart = openingEntity.dateStart,
                        icon =  openingEntity.circle?.logoUrl?.let { url -> baseUrl + url },
                        feeling = openingEntity.feeling ?: "",
                        available = calculateAvailable(openingEntity).coerceAtLeast(0),
                        outOf = openingEntity.maxOrder,
                        banner = openingEntity.prUrl.let { url -> baseUrl + url },
                        day = timeService.format(openingEntity.dateStart, "u")?.toInt().let { daysOfTheWeek[it ?: 0] },
                        comment = "${timeService.format(openingEntity.orderEnd, "u")?.toInt().let { daysOfTheWeek[it ?: 0] }} " +
                                "${timeService.format(openingEntity.orderEnd, "HH:mm")}-ig rendelhető",
                        circleUrl = openingEntity.circle?.alias?.let { alias -> baseUrl + "p/" + alias } ?: (baseUrl + "p/" + (openingEntity.circle?.id ?: 0)),
                        circleColor = openingEntity.circle?.cssClassName ?: "none"
                ) }
    }

    private val trashpandaVoters: ConcurrentHashMap<String, Int> = ConcurrentHashMap<String, Int>()

//    @PostMapping("/easteregg/trashpanda/{feedback}")
//    @ResponseBody
    fun trashpandaVote(
            request: HttpServletRequest,
            @PathVariable feedback: Int
    ): String {
        val ip = request.remoteAddr ?: ""
        if (trashpandaVoters.containsKey(ip)) {
            if (feedback == 1 || feedback == 2)
                trashpandaVoters[ip] = feedback
        } else {
            trashpandaVoters[ip] = feedback
        }
        return "OK"
    }

//    @GetMapping("/admin/trashpanda")
//    @ResponseBody
    fun trashpandaShow(request: HttpServletRequest): String {
        if (request.getUserIfPresent()?.sysadmin == true) {
            return "Good: " + trashpandaVoters.values.count { it == 1 } +
                    " Bad: " + trashpandaVoters.values.count { it == 2 } +
                    " OK: " + trashpandaVoters.values.count { it == 3 } +
                    " Side: " + trashpandaVoters.values.count { it == 4 } +
                    " HACK: " + trashpandaVoters.values.count { it < 1 || it > 4 }
        } else {
            return "Nice try!"
        }
    }

//    @GetMapping("/admin/trashpanda/raw")
//    @ResponseBody
    fun trashpandaShowRaw(request: HttpServletRequest): String {
        if (request.getUserIfPresent()?.sysadmin == true) {
            return trashpandaVoters.entries.toString()
        } else {
            return "Nice try!"
        }
    }
}
