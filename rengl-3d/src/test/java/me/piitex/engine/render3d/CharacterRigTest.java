package me.piitex.engine.render3d;

import me.piitex.engine.render3d.math.Mat4;
import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sharing one skeleton and animation library between several parts and characters: skin binding, clip retargeting and
 * bone offsets. No GPU needed.
 */
class CharacterRigTest {
    /** hips > spine > head, as a loaded model would have. */
    private static Node3D skeleton() {
        Node3D armature = new Node3D("Armature");
        Node3D hips = new Node3D("hips");
        Node3D spine = new Node3D("spine");
        Node3D head = new Node3D("head");
        spine.setPosition(0, 1, 0);
        head.setPosition(0, 1, 0);
        armature.add(hips.add(spine.add(head)));
        return armature;
    }

    private static AnimationClip bob(Node3D hips, Node3D head) {
        return new AnimationClip("bob", 2f, List.of(
                new AnimationClip.Channel(hips, AnimationClip.Path.TRANSLATION, new float[]{0f, 1f},
                        new float[]{0, 0, 0, 0, 2, 0}, AnimationClip.Interpolation.LINEAR),
                new AnimationClip.Channel(head, AnimationClip.Path.SCALE, new float[]{0f},
                        new float[]{1, 1, 1}, AnimationClip.Interpolation.LINEAR)));
    }

    @Test
    void skinRebindsToTheBodysJoints() {
        Node3D body = skeleton();
        Node3D hairSkeleton = skeleton();
        Node3D hair = new Node3D("hair");
        Skin own = new Skin(List.of(hairSkeleton.find("spine"), hairSkeleton.find("head")),
                List.of(Mat4.identity(), Mat4.translation(new Vec3(0, -1, 0))));
        hair.setSkin(own);

        Skin bound = own.rebind(body);
        assertSame(body.find("spine"), bound.joints().get(0));
        assertSame(body.find("head"), bound.joints().get(1));
        assertSame(own.inverseBind(1), bound.inverseBind(1));
        assertSame(hairSkeleton.find("spine"), own.joints().get(0), "the original skin is untouched");

        body.find("head").setPosition(0, 5, 0);
        assertEquals(5f, bound.jointMatrix(1).transformPoint(new Vec3(0, 0, 0)).y(), 1e-4f);
    }

    @Test
    void bindSkeletonRebindsEveryPartOfTheNode() {
        Node3D body = skeleton();
        Node3D hairSkeleton = skeleton();
        Skin shared = new Skin(List.of(hairSkeleton.find("head")), List.of(Mat4.identity()));
        Node3D hair = new Node3D("hair");
        Node3D front = new Node3D("front");
        Node3D back = new Node3D("back");
        front.setSkin(shared);
        back.setSkin(shared);
        hair.add(front).add(back).add(hairSkeleton);

        assertSame(hair, hair.bindSkeleton(body));
        assertSame(body.find("head"), front.getSkin().joints().get(0));
        assertSame(front.getSkin(), back.getSkin(), "parts that shared a skin still share one");
    }

    @Test
    void rebindNamesTheMissingBones() {
        Node3D body = skeleton();
        Node3D extra = new Node3D("ponytail");
        Skin skin = new Skin(List.of(body.find("head"), extra), List.of(Mat4.identity(), Mat4.identity()));
        var e = assertThrows(IllegalArgumentException.class, () -> skin.rebind(body));
        assertTrue(e.getMessage().contains("ponytail"), e.getMessage());
    }

    @Test
    void retargetedClipDrivesAnotherSkeleton() {
        Node3D a = skeleton();
        Node3D b = a.copy();
        AnimationClip clip = bob(a.find("hips"), a.find("head"));
        AnimationClip forB = clip.retarget(b);

        assertEquals("bob", forB.name());
        assertEquals(clip.duration(), forB.duration());
        assertSame(b.find("hips"), forB.channels().get(0).target());
        assertSame(clip.channels().get(0).values(), forB.channels().get(0).values(), "keys are shared, not copied");

        AnimationPlayer player = new AnimationPlayer(List.of(forB));
        player.play("bob").setTime(0.5f);
        assertEquals(1f, b.find("hips").getPosition().y(), 1e-4f);
        assertEquals(0f, a.find("hips").getPosition().y(), 1e-4f, "the original character does not move");
    }

    @Test
    void retargetSkipsUnknownBonesButNotEverything() {
        Node3D a = skeleton();
        Node3D small = new Node3D("Armature");
        small.add(new Node3D("hips"));
        AnimationClip forSmall = bob(a.find("hips"), a.find("head")).retarget(small);
        assertEquals(1, forSmall.channels().size());

        Node3D stranger = new Node3D("Other");
        stranger.add(new Node3D("pelvis"));
        assertThrows(IllegalArgumentException.class, () -> bob(a.find("hips"), a.find("head")).retarget(stranger));
    }

    @Test
    void playerRetargetsALibraryForACrowd() {
        Node3D template = skeleton();
        AnimationPlayer library = new AnimationPlayer(List.of(bob(template.find("hips"), template.find("head"))));
        Node3D one = template.copy();
        Node3D two = template.copy();
        AnimationPlayer p1 = library.retarget(one);
        AnimationPlayer p2 = library.retarget(two);
        p1.play("bob").setTime(1f);
        p2.play("bob").setTime(0.5f);
        assertEquals(2f, one.find("hips").getPosition().y(), 1e-4f);
        assertEquals(1f, two.find("hips").getPosition().y(), 1e-4f);
    }

    @Test
    void offsetsSurviveTheAnimationOverwritingScaleAndPosition() {
        Node3D rig = skeleton();
        AnimationPlayer player = new AnimationPlayer(List.of(bob(rig.find("hips"), rig.find("head"))));
        BoneOffsets offsets = new BoneOffsets(rig);
        player.setBoneOffsets(offsets);
        offsets.scale("head", 1.5f).position("hips", new Vec3(0, 0.25f, 0));

        player.play("bob");
        for (int frame = 0; frame < 5; frame++) player.update(0.1f);
        assertEquals(1.5f, rig.find("head").getScale().x(), 1e-4f, "the scale track says 1; the offset is on top, not compounded");
        assertEquals(1.5f, rig.find("head").getScale().y(), 1e-4f);
        assertEquals(1f + 0.25f, rig.find("hips").getPosition().y(), 1e-4f, "animated height plus the offset");
    }

    @Test
    void offsetsOnABoneTheClipDoesNotAnimateDoNotCompound() {
        Node3D rig = skeleton();
        AnimationPlayer player = new AnimationPlayer(List.of(bob(rig.find("hips"), rig.find("head"))));
        BoneOffsets offsets = new BoneOffsets(rig);
        player.setBoneOffsets(offsets);
        offsets.scale("spine", 2f);
        player.play("bob");
        for (int frame = 0; frame < 10; frame++) player.update(0.05f);
        assertEquals(2f, rig.find("spine").getScale().x(), 1e-4f);
    }

    @Test
    void changingAnOffsetWhilePausedShowsAtOnce() {
        Node3D rig = skeleton();
        AnimationPlayer player = new AnimationPlayer(List.of(bob(rig.find("hips"), rig.find("head"))));
        BoneOffsets offsets = new BoneOffsets(rig);
        player.setBoneOffsets(offsets);
        player.play("bob").setTime(0.5f).setPlaying(false);

        offsets.scale("head", 2f);
        assertEquals(2f, rig.find("head").getScale().x(), 1e-4f);
        offsets.scale("head", 3f);
        assertEquals(3f, rig.find("head").getScale().x(), 1e-4f, "set again, not multiplied again");
        offsets.clear("head");
        assertEquals(1f, rig.find("head").getScale().x(), 1e-4f);
        assertEquals(1f, rig.find("hips").getPosition().y(), 1e-4f, "the paused pose is kept");
    }

    @Test
    void offsetsWorkWithoutAPlayer() {
        Node3D rig = skeleton();
        BoneOffsets offsets = new BoneOffsets(rig);
        offsets.scale("head", 2f).apply();
        offsets.apply();
        assertEquals(2f, rig.find("head").getScale().x(), 1e-4f);
        assertEquals(new Vec3(2, 2, 2), offsets.getScale("head"));
        assertEquals(Vec3.ONE, offsets.getScale("spine"));
        assertThrows(IllegalArgumentException.class, () -> offsets.scale("tail", 2f));
    }
}
