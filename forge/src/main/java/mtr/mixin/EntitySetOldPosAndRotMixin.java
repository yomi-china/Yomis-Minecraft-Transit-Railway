package mtr.mixin;

import mtr.client.ClientData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class EntitySetOldPosAndRotMixin {

	@Unique
	private double savedXo, savedYo, savedZo;
	@Unique
	private float savedYRotO, savedXRotO;

	@Inject(method = "setPosAndOldPos", at = @At("HEAD"))
	private void beforeSetOldPosAndRot(double x, double y, double z, CallbackInfo ci) {
		final Entity self = (Entity) (Object) this;
		if (self instanceof LocalPlayer && ClientData.isRiding(self.getUUID())) {
			savedXo = self.xo;
			savedYo = self.yo;
			savedZo = self.zo;
			savedYRotO = self.yRotO;
			savedXRotO = self.xRotO;
		}
	}

	@Inject(method = "setPosAndOldPos", at = @At("RETURN"))
	private void afterSetOldPosAndRot(double x, double y, double z, CallbackInfo ci) {
		final Entity self = (Entity) (Object) this;
		if (self instanceof LocalPlayer && ClientData.isRiding(self.getUUID())) {
			self.xo = savedXo;
			self.yo = savedYo;
			self.zo = savedZo;
			self.yRotO = savedYRotO;
			self.xRotO = savedXRotO;
		}
	}
}
