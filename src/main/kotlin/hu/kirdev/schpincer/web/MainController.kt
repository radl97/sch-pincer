package hu.kirdev.schpincer.web

import hu.kirdev.schpincer.model.CardType
import hu.kirdev.schpincer.model.CircleEntity
import hu.kirdev.schpincer.model.OrderStatus
import hu.kirdev.schpincer.service.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.servlet.http.HttpServletRequest

@Controller
open class MainController {

    @Autowired
    private lateinit var circles: CircleService

    @Autowired
    private lateinit var openings: OpeningService

    @Autowired
    private lateinit var orders: OrderService

    @Autowired
    private lateinit var timeService: TimeService

    @Autowired
    private lateinit var statService: StatisticsService

    @GetMapping("/")
    fun root(request: HttpServletRequest, model: Model): String {
        val circlesList: List<CircleEntity> = circles.findAllForMenu()
        model.addAttribute("circles", circlesList)

        val random = ArrayList(circlesList)
        random.shuffle()
        model.addAttribute("circlesRandom", random)

        val opens = openings.findUpcomingOpenings()
        model.addAttribute("opener", if (opens.isNotEmpty()) opens[0] else null)
        model.addAttribute("openings", openings.findNextWeek())

        model.addAttribute("orders", Collections.EMPTY_LIST)
        if (request.hasUser()) {
            model.addAttribute("orders", orders.findAll(request.getUserId())
                    .filter { it.status === OrderStatus.ACCEPTED }
                    .filter { it.date >= (System.currentTimeMillis() - (1000 * 60 * 60 * 24 * 7 * 3)) }
                    .take(3))
        }
        model.addAttribute("timeService", timeService)
        return "index"
    }

    @GetMapping("/items")
    fun items(@RequestParam(name = "q", defaultValue = "") keyword: String, model: Model, request: HttpServletRequest): String {
        model.addAttribute("circles", circles.findAllForMenu())
        model.addAttribute("searchMode", "" != keyword)
        model.addAttribute("keyword", keyword)
        model.addAttribute("card", (request.getUserIfPresent()?.cardType ?: CardType.DO).name)
        return "items"
    }

    @GetMapping("/szor")
    fun circle(model: Model): String {
        model.addAttribute("circles", circles.findAllForMenu())
        model.addAttribute("circlesWithOpening", circles.findAllForInfo())
        model.addAttribute("timeService", timeService)
        return "circle"
    }

    @GetMapping("/circle/{circle}")
    fun circleSpecific(@PathVariable circle: String, model: Model, request: HttpServletRequest): String {
        model.addAttribute("circles", circles.findAllForMenu())
        model.addAttribute("card", (request.getUserIfPresent()?.cardType ?: CardType.DO).name)
        if (circle.matches("^\\d+$".toRegex())) {
            val id = circle.toLong()
            model.addAttribute("selectedCircle", circles.getOne(id))
            model.addAttribute("nextOpening", openings.findNextStartDateOf(id))
        } else {
            val circleEntity: CircleEntity = circles.findByAlias(circle)
            model.addAttribute("selectedCircle", circleEntity)
            model.addAttribute("nextOpening", openings.findNextStartDateOf(circleEntity.id))
        }
        model.addAttribute("timeService", timeService)
        return "circleProfile"
    }

    @GetMapping(path = ["/provider/{circle}", "/p/{circle}"])
    fun circleSpecificAlias(@PathVariable circle: String, model: Model, request: HttpServletRequest): String {
        return circleSpecific(circle, model, request)
    }

    @GetMapping("/profile")
    fun profile(request: HttpServletRequest, model: Model): String {
        model.addAttribute("orders", this.orders.findAll(request.getUserId()))
        model.addAttribute("circles", circles.findAllForMenu())
        model.addAttribute("timeService", timeService)
        model.addAttribute("uid", request.getUserId().sha256().substring(0, 6))
        return "profile"
    }

    private val statsViews: ConcurrentHashMap<String, String> = ConcurrentHashMap<String, String>()

    @GetMapping("/stats")
    fun stats(request: HttpServletRequest, model: Model): String {
        model.addAttribute("circles", circles.findAllForMenu())
        val user = request.getUser()
        statsViews.computeIfPresent(user.uid, { a, b -> b + "+1"})
        statsViews.computeIfAbsent(user.uid, { user.name + ";" + System.currentTimeMillis() + ";1" })
        statService.getDetailsForUser(user).entries.forEach { model.addAttribute(it.key, it.value) }
        return "stats"
    }

    @GetMapping("/admin/stats-insight")
    @ResponseBody
    fun statsInsights(request: HttpServletRequest): String {
        if (request.getUserIfPresent()?.sysadmin == true) {
            return statsViews.values.toString()
        } else {
            return "Nice try!"
        }
    }

}
