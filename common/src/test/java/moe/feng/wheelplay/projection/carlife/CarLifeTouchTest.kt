package moe.feng.wheelplay.projection.carlife

import com.shilapi.xcertplay.projection.ProjectionContact
import org.junit.Assert.*
import org.junit.Test

class CarLifeTouchTest {
    @Test fun resizeReleasesOldLeaseAndUsesActualNegotiatedCorners() {
        val sent = mutableListOf<List<Int>>()
        val touch = CarLifeTouch { a, x, y, _, _ -> sent += listOf(a, x, y) }
        touch.accept(listOf(ProjectionContact(0, 1.0, 1.0, true)))
        touch.resize(1920, 1080)
        touch.accept(listOf(ProjectionContact(0, 1.0, 1.0, true)))
        touch.resize(1920, 1080)
        touch.release()
        assertEquals(listOf(listOf(0,1279,719), listOf(1,1279,719),
            listOf(0,1919,1079), listOf(1,1919,1079)), sent)
    }
    @Test fun twoFingersRetainSlotsAcrossReorderingAndReleaseBoth() {
        val sent = mutableListOf<List<Int>>()
        val touch = CarLifeTouch { a,x,y,px,py -> sent += listOf(a,x,y,px,py) }
        fun p(id: Int, x: Double) = ProjectionContact(id,x,.5,true)
        touch.accept(listOf(p(8,.2),p(3,.8)))
        touch.accept(listOf(p(3,.9),p(8,.1)))
        touch.accept(listOf(p(8,.1)))
        touch.accept(listOf(p(8,.1),p(4,.7)))
        touch.release()
        assertEquals(listOf(0,261,2,262,261,262,1),sent.map { it[0] })
        assertEquals(listOf(2,128,360,1151,360),sent[2])
    }
    @Test fun primaryLiftRebasesSurvivorAndResizeReleasesTwoFingers() {
        val sent = mutableListOf<Int>()
        val touch = CarLifeTouch { a,_,_,_,_ -> sent += a }
        val a = ProjectionContact(5,.2,.5,true); val b = ProjectionContact(9,.8,.5,true)
        touch.accept(listOf(a,b)); touch.accept(listOf(b))
        touch.accept(listOf(b,a)); touch.resize(640,480); touch.release()
        assertEquals(listOf(0,261,262,1,0,261,262,1),sent)
    }
    @Test fun mapsCornersAndReleasesMissingContact() {
        val sent = mutableListOf<List<Int>>()
        val touch = CarLifeTouch { a, x, y, _, _ -> sent += listOf(a, x, y) }
        touch.accept(listOf(ProjectionContact(0, 0.0, 0.0, true)))
        touch.accept(listOf(ProjectionContact(0, 1.0, 1.0, true)))
        touch.accept(emptyList())
        touch.release()
        assertEquals(listOf(listOf(0,0,0), listOf(2,1279,719), listOf(1,1279,719)), sent)
    }
    @Test fun newPointerReleasesOldBeforeDownAndExplicitUpDoesNotMove() {
        val sent = mutableListOf<Int>()
        val touch = CarLifeTouch { a, _, _, _, _ -> sent += a }
        touch.accept(listOf(ProjectionContact(0, .5, .5, true)))
        touch.accept(listOf(ProjectionContact(0, .5, .5, true)))
        touch.accept(listOf(ProjectionContact(1, .1, .1, true)))
        touch.accept(listOf(ProjectionContact(1, .1, .1, false)))
        assertEquals(listOf(0,1,0,1), sent)
    }
}
